package com.opticdev.core.sourcegear.sync

import com.opticdev.core.sourcegear.actors.ActorCluster
import com.opticdev.core.sourcegear.context.FlatContextBase
import com.opticdev.core.sourcegear.{Render, SGContext, SourceGear}
import com.opticdev.core.sourcegear.graph.ProjectGraph
import com.opticdev.core.sourcegear.graph.edges.DerivedFrom
import com.opticdev.core.sourcegear.graph.model.{BaseModelNode, ModelNode}
import com.opticdev.core.sourcegear.objects.annotations.TagAnnotation
import com.opticdev.core.sourcegear.project.ProjectBase
import com.opticdev.parsers.graph.{BaseNode, CommonAstNode}
import com.opticdev.sdk.RenderOptions
import com.opticdev.sdk.descriptions.transformation.{StagedNode, Transformation}
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue
import play.api.libs.json.{JsObject, JsString}
import scalax.collection.edge.LkDiEdge
import scalax.collection.mutable.Graph
import com.opticdev.marvin.common.helpers.InRangeImplicits._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Try
import com.opticdev.core.sourcegear.graph.GraphImplicits._
import com.opticdev.core.sourcegear.mutate.MutationSteps.{collectFieldChanges, combineChanges, handleChanges}
import com.opticdev.parsers.ParserBase

///only call from a project actor

object DiffSyncGraph {

  def calculateDiff(projectGraph: ProjectGraph)(implicit project: ProjectBase, includeNoChange: Boolean = false) : SyncPatch = {

    implicit val actorCluster = project.actorCluster
    implicit val sourceGear = project.projectSourcegear

    val resultsFromSyncGraph = SyncGraph.getSyncGraph(projectGraph.asInstanceOf[ProjectGraph])
    val projectPlusSyncGraph: Graph[BaseNode, LkDiEdge] = resultsFromSyncGraph.syncGraph.asInstanceOf[Graph[BaseNode, LkDiEdge]]
    implicit val graph: Graph[BaseNode, LkDiEdge] = projectPlusSyncGraph.filter(projectPlusSyncGraph.having(edge = (e) => e.isLabeled && e.label.isInstanceOf[DerivedFrom]))

    val startingNodes= graph.nodes.collect { case n if n.dependencies.isEmpty => n.value.asInstanceOf[BaseModelNode] }.toVector

    def compareDiffAlongPath(sourceNode: BaseModelNode, predecessorDiff: Option[SyncDiff] = None) : Vector[SyncDiff] = {
      val sourceValue = {
        if (predecessorDiff.exists(_.newValue.isDefined)) {
          predecessorDiff.get.newValue.get
        } else {
          implicit val sourceGearContext: SGContext = sourceNode.getContext().get
          sourceNode.expandedValue()
        }
      }
      sourceNode.labeledDependents.toVector.flatMap {
        case (label: DerivedFrom, targetNode: BaseModelNode) => {

          val diff = compareNode(label, sourceNode, sourceValue, targetNode)

          Vector(diff) ++ compareDiffAlongPath(targetNode, Some(diff))

        }
        case _ => Vector()   ///should never be hit
      }
    }

    SyncPatch(startingNodes.flatMap(i=> compareDiffAlongPath(i)).filterNot(i=> i.isInstanceOf[NoChange] && !includeNoChange), resultsFromSyncGraph.warnings)
  }

  def compareNode(label: DerivedFrom, sourceNode: BaseModelNode, sourceValue: JsObject, targetNode: BaseModelNode)(implicit sourceGear: SourceGear, actorCluster: ActorCluster, graph: Graph[BaseNode, LkDiEdge], project: ProjectBase) = {
    import com.opticdev.core.sourcegear.graph.GraphImplicits._
    val extractValuesTry = for {
      transformation <- Try(sourceGear.findTransformation(label.transformationRef).getOrElse(throw new Error(s"No Transformation with id '${label.transformationRef.full}' found")))
      transformationResult <- transformation.transformFunction.transform(sourceValue, label.askAnswers)
      (currentValue, linkedModel, context) <- Try {
        implicit val sourceGearContext: SGContext = targetNode.getContext().get
        (targetNode.expandedValue(), targetNode.resolved(), sourceGearContext)
      }
      (expectedValue, expectedRaw) <- Try {

        val prefixedFlatContent: FlatContextBase = sourceGear.flatContext.prefix(transformation.packageId.packageId)

        val stagedNode = transformationResult.toStagedNode(Some(RenderOptions(
          lensId = Some(targetNode.lensRef.full)
        )))

        implicit val sourceGearContext: SGContext = targetNode.getContext().get
        val tagVector = sourceGearContext.astGraph.nodes.filter(_.value match {
          case mn: BaseModelNode if mn.tag.isDefined &&
            stagedNode.tags.map(_._1).contains(mn.tag.get.tag) &&
            stagedNode.tagsMap(mn.tag.get.tag).schema.matchLoose(mn.schemaId) && //reduces ambiguity. need a long term fix.
            linkedModel.root.hasChild(mn.resolved().root)(sourceGearContext.astGraph) => true
          case _ => false
        }).map(i=> (i.value.asInstanceOf[BaseModelNode].tag.get.tag, i.value.asInstanceOf[BaseModelNode]))
          .toVector
          .sortBy(t=> stagedNode.tags.indexWhere(_._1 == t))

        val tagPatches: Seq[SyncDiff] = tagVector.collect {
          case (tag, targetTagNode) => {
            val tagStaged = stagedNode.tagsMap(tag)
            val tagStagedValues = expectedValuesForStagedNode(tagStaged, prefixedFlatContent)
            val targetTagValue = targetTagNode.expandedValue()
            if (tagStagedValues._1 == targetTagValue) {
              NoChange(label, targetTagNode.tag)
            } else {
              UpdatedTag(tag, label, targetTagNode, targetTagValue, tagStagedValues._1, null)
            }
          }
        }

        val rawAfterTags = tagPatches.foldLeft(sourceGearContext.fileContents.substring(linkedModel.root)) {
          case (current: String, ut: UpdatedTag) =>
            updateNodeFromRaw(stagedNode, Some(ut.modelNode.tag.get), ut.after, current)(sourceGear, prefixedFlatContent, context.parser)

          case (c, p) => c
        }

        val (expectedValue, expectedRaw) = expectedValuesForStagedNode(stagedNode, prefixedFlatContent)
        val newExpectedRaw = updateNodeFromRaw(stagedNode, None, expectedValue, rawAfterTags)(sourceGear, prefixedFlatContent, context.parser)
        (expectedValue, newExpectedRaw)
      }
    } yield (expectedValue, currentValue, linkedModel, expectedRaw, context)

    if (extractValuesTry.isSuccess) {
      val (expectedValue, currentValue, linkedModel, expectedRaw, context) = extractValuesTry.get
      if (expectedValue == currentValue) {
        NoChange(label)
      } else {
        Replace(label, currentValue, expectedValue,
          RangePatch(linkedModel.root.range, expectedRaw, context.file, context.fileContents))
      }
    } else {
      println(extractValuesTry.failed.get.printStackTrace())
      ErrorEvaluating(label, extractValuesTry.failed.get.getMessage, targetNode.resolved().toDebugLocation)
    }

  }

  def expectedValuesForStagedNode(stagedNode: StagedNode, context: FlatContextBase)(implicit sourceGear: SourceGear): (JsObject, String) = {
    val generatedNode = Render.fromStagedNode(stagedNode)(sourceGear, context).get
    val value = generatedNode._3.renderer.parseAndGetModel(generatedNode._2)(sourceGear, context).get
    val raw = generatedNode._2
    (value, raw)
  }

  def updateNodeFromRaw(masterStagedNode: StagedNode, targetNodeOption: Option[TagAnnotation], newValue: JsObject, raw: String)(implicit sourceGear: SourceGear, context: FlatContextBase, parser: ParserBase) = {
    import com.opticdev.core.sourcegear.mutate.MutationImplicits._
    val lens = Render.resolveLens(masterStagedNode).get
    val (value, astGraph, modelNode) = lens.renderer.parseAndGetModelWithGraph(raw).get

    implicit val sourceGearContext = SGContext.forRender(sourceGear, astGraph, parser.parserRef)
    implicit val fileContents = raw

    if (targetNodeOption.isDefined) {
      val tag = targetNodeOption.get
      val taggedModelNode = astGraph.modelNodes.find(_.tag.contains(tag)).get.asInstanceOf[ModelNode].resolveInGraph[CommonAstNode](astGraph)
      taggedModelNode.update(newValue)
    } else {
      modelNode.resolveInGraph[CommonAstNode](astGraph).update(newValue)
    }

  }

}

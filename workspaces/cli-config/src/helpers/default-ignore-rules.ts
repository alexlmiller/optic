export const defaultIgnoreRules = [
  'OPTIONS (.*)',
  'HEAD (.*)',
  'GET (.*).htm',
  'GET (.*).html',
  'GET (.*).ico',
  'GET (.*).css',
  'GET (.*).js',
  'GET (.*).woff',
  'GET (.*).woff2',
  'GET (.*).png',
  'GET (.*).jpg',
  'GET (.*).jpeg',
  'GET (.*).svg',
  'GET (.*).gif',
];

export const defaultIgnoreFile = `
# Default Ignore Rules
# Learn to configure your own at http://localhost:4000/docs/using/advanced-configuration#ignoring-api-paths
${defaultIgnoreRules.join('\n')}
`.trim();

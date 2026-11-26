const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '..');

test('Expo entry belongs to this project rather than a dependency store', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  assert.equal(pkg.main, 'index.ts');
  const entry = fs.readFileSync(path.join(root, pkg.main), 'utf8');
  assert.match(entry, /import\s+\{\s*registerRootComponent\s*\}\s+from\s+["']expo["']/);
  assert.match(entry, /import\s+App\s+from\s+["']\.\/App["']/);
  assert.match(entry, /registerRootComponent\(App\)/);
  const sonar = fs.readFileSync(path.join(root, 'ci/sonar-quality-gate.sh'), 'utf8');
  assert.match(sonar, /-Dsonar\.sources=index\.ts,App\.tsx,src/);
  const config = JSON.parse(fs.readFileSync(path.join(root, 'tsconfig.sonar.json'), 'utf8'));
  assert(config.include.includes('index.ts'));
});

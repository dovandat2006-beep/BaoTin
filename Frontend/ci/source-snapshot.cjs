const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const{spawnSync}=require('node:child_process');
const ignored=new Set(['.git','.gradle','.scannerwork','.hallmark','.impeccable','node_modules','android','ios','build','coverage','.expo','.verification','.redesign-artifacts','test-results','playwright-report','playwright-report-reports']);
const text=/\.(?:[cm]?js|jsx|ts|tsx|json|md|ya?ml|gradle|kt|kts|properties|sh|conf|css|xml|html|toml|py|txt|ps1|gitignore|editorconfig)$/i;
/** Canonical content snapshot, not tar timestamps or a mutable commit label. */
function sourceSnapshot(root){
 root=fs.realpathSync(root);const rows=[];
 const git=spawnSync('git',['-C',root,'ls-files','--cached','--others','--exclude-standard','-z'],{encoding:'utf8',windowsHide:true});
 const files=git.status===0?new Set(git.stdout.split('\0').filter(Boolean)):null;
 const directories=new Set();if(files)for(const name of files){const parts=name.split('/');parts.pop();while(parts.length){directories.add(parts.join('/'));parts.pop();}}
 function walk(directory){for(const entry of fs.readdirSync(directory,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name,'en'))){const relative=path.relative(root,path.join(directory,entry.name)).split(path.sep).join('/');
  if(ignored.has(entry.name)||entry.name.startsWith('dist')||relative==='public/downloads')continue;
  if(entry.isSymbolicLink())throw new Error('Source snapshot rejects symlinks');
  if(entry.isDirectory()){if(files&&!directories.has(relative))continue;walk(path.join(directory,entry.name));continue;}
  if(files&&!files.has(relative))continue;
  if(!entry.isFile()||entry.name==='source.tar'||entry.name.endsWith('.log')||entry.name.startsWith('.env')&&!/\.example$/.test(entry.name)||/\.(?:apk|aab|jks|keystore|token|secret|pem|key)$/i.test(entry.name))continue;
  let data=fs.readFileSync(path.join(directory,entry.name));if(text.test(entry.name)||entry.name.endsWith('.example')||['Jenkinsfile','Dockerfile','.gitignore','.dockerignore','.gitattributes'].includes(entry.name))data=Buffer.from(data.toString('utf8').replace(/\r\n/g,'\n'));
  rows.push(relative+'\0'+crypto.createHash('sha256').update(data).digest('hex')+'\n');
 }}walk(root);return crypto.createHash('sha256').update(rows.sort().join('')).digest('hex');
}
if(require.main===module){try{console.log(sourceSnapshot(path.resolve(process.argv[2]||'.')));}catch(error){console.error(error.message);process.exitCode=1;}}module.exports={sourceSnapshot};

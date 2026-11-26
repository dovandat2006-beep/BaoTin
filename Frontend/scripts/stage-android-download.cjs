const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const{validateGatewayOrigin}=require('../ci/release-api-config.cjs');
function stageAndroidDownload(source,destination,expectedSnapshot,expectedOrigin){
 validateGatewayOrigin(expectedOrigin);
 if(!/^[a-f0-9]{64}$/.test(expectedSnapshot||'')||!path.isAbsolute(source)||!path.isAbsolute(destination)||fs.lstatSync(source).isSymbolicLink())throw new Error('Exact source snapshot and regular absolute directories required');
 const file=path.join(source,'android.json');if(fs.lstatSync(file).isSymbolicLink())throw new Error('Manifest symlink rejected');const m=JSON.parse(fs.readFileSync(file,'utf8'));
 if(m.schemaVersion!==1||m.sourceSnapshotSha256!==expectedSnapshot||m.gatewayOrigin!==expectedOrigin||!/^[a-f0-9]{64}$/.test(m.sha256||'')||m.url!==`/downloads/baotin-${m.sha256}.apk`||!Number.isSafeInteger(m.bytes)||m.bytes<1||m.bytes>1024**3||!/^[a-f0-9]{64}$/.test(m.signingCertificateSha256||''))throw new Error('Stale or invalid APK publication manifest');
 const apk=path.join(source,`baotin-${m.sha256}.apk`);if(fs.lstatSync(apk).isSymbolicLink()||!fs.statSync(apk).isFile()||fs.statSync(apk).size!==m.bytes||crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex')!==m.sha256)throw new Error('APK hash/size mismatch');
 if(fs.existsSync(destination)&&fs.lstatSync(destination).isSymbolicLink())throw new Error('Target symlink rejected');fs.mkdirSync(destination,{recursive:true});
 for(const name of ['android.json',path.basename(apk)])fs.copyFileSync(path.join(source,name),path.join(destination,name),fs.constants.COPYFILE_EXCL);
 return m;
}
if(require.main===module){try{const m=stageAndroidDownload(path.resolve(process.argv[2]),path.resolve(process.argv[3]),process.argv[4],process.env.EXPO_PUBLIC_API_URL);console.log(`ANDROID_DOWNLOAD_STAGE PASS sourceSnapshot=matched gatewayOrigin=matched bytes=${m.bytes} sha256=${m.sha256}`);}catch(error){console.error(error.message);process.exitCode=1;}}
module.exports={stageAndroidDownload};

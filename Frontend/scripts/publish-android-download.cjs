const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const{validateGatewayOrigin}=require('../ci/release-api-config.cjs');
function publishAndroidDownload(options){
 const {apk,verification:v,destination,version,versionCode,sourceSnapshotSha256}=options;
 if(typeof apk!=='string'||!path.isAbsolute(apk)||path.extname(apk).toLowerCase()!=='.apk'||fs.lstatSync(apk).isSymbolicLink()||!fs.statSync(apk).isFile())throw new Error('A regular absolute APK path is required');
 const bytes=fs.readFileSync(apk),sha256=crypto.createHash('sha256').update(bytes).digest('hex');
 if(!v||v.status!=='PASS'||v.type!=='APK'||v.sha256!==sha256||v.bytes!==bytes.length||v.hermes!==true||v.privateBuildFilesPackaged!==false||v.apkSigningVerified!==true||v.zip16KBAligned!==true||v.sourceSnapshotSha256!==sourceSnapshotSha256||v.packageName!=='vn.baotin.app'||v.versionName!==version||v.versionCode!==versionCode||!/^[a-f0-9]{64}$/.test(v.signingCertificateSha256||''))throw new Error('Verified matching APK/signing/source report required');
 if(!bytes.subarray(0,4).equals(Buffer.from([80,75,3,4]))||typeof destination!=='string'||!path.isAbsolute(destination)||!/^[a-f0-9]{64}$/.test(sourceSnapshotSha256||'')||!/^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$/.test(version||'')||!Number.isSafeInteger(versionCode)||versionCode<1)throw new Error('Invalid release metadata');
 const gatewayOrigin=validateGatewayOrigin(v.gatewayOrigin);
 const name=`baotin-${sha256}.apk`,manifest={schemaVersion:1,version,versionCode,bytes:bytes.length,sha256,url:`/downloads/${name}`,sourceSnapshotSha256,gatewayOrigin,signingCertificateSha256:v.signingCertificateSha256,publishedAt:new Date().toISOString()};
 fs.mkdirSync(destination,{recursive:true});if(fs.lstatSync(destination).isSymbolicLink())throw new Error('Publication directory must not be a symlink');
 const target=path.join(destination,name);if(fs.existsSync(target)){if(crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex')!==sha256)throw new Error('Existing content-addressed artifact differs');}else fs.copyFileSync(apk,target,fs.constants.COPYFILE_EXCL);
 const pending=path.join(destination,'android.json.pending');if(fs.existsSync(pending))throw new Error('Pending publication already exists; inspect before retry');fs.writeFileSync(pending,JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});fs.renameSync(pending,path.join(destination,'android.json'));
 return manifest;
}
if(require.main===module){try{const args={};for(let i=2;i<process.argv.length;i+=2)args[process.argv[i].replace(/^--/,'')]=process.argv[i+1];const result=publishAndroidDownload({apk:args.apk,verification:JSON.parse(fs.readFileSync(args.verification,'utf8')),destination:args.destination,version:args.version,versionCode:Number(args['version-code']),sourceSnapshotSha256:args['source-snapshot']});console.log(`ANDROID_DOWNLOAD_PUBLISH PASS bytes=${result.bytes} sha256=${result.sha256} url=${result.url}`);}catch(error){console.error(error.message);process.exitCode=1;}}
module.exports={publishAndroidDownload};

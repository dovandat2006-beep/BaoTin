const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
function parseAndroidProof(badging,signatures){
 const pkg=badging.match(/^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'/m),certificate=signatures.match(/(?:Signer #1 certificate|V[23](?:\.[0-9]+)? Signer: certificate) SHA-256 digest: ([a-fA-F0-9]{64})/);
 if(!pkg||pkg[1]!=='vn.baotin.app'||!certificate||badging.includes('application-debuggable')||!badging.includes("uses-permission: name='android.permission.INTERNET'"))throw new Error('APK package/signing/release proof invalid');
 if(!signatures.includes('Verified using v2 scheme (APK Signature Scheme v2): true')&&!signatures.includes('Verified using v3 scheme (APK Signature Scheme v3): true'))throw new Error('Modern APK signature proof required');
 return{packageName:pkg[1],versionCode:Number(pkg[2]),versionName:pkg[3],apkSigningVerified:true,releaseNonDebuggable:true,signingCertificateSha256:certificate[1].toLowerCase()};
}
function main(){const args={};for(let i=2;i<process.argv.length;i+=2)args[process.argv[i].replace(/^--/,'')]=process.argv[i+1];const apk=path.resolve(args.apk),output=path.resolve(args.output),sdk=path.resolve(args.sdk),snapshot=args['source-snapshot'];if(!/^[a-f0-9]{64}$/.test(snapshot||'')||!fs.statSync(apk).isFile()||!args.origin)throw new Error('APK/source snapshot/origin required');
 const tools=fs.readdirSync(path.join(sdk,'build-tools')).filter(n=>/^\d+\.\d+\.\d+$/.test(n)).sort((a,b)=>b.localeCompare(a,undefined,{numeric:true}));if(!tools.length)throw new Error('Android build-tools required');const home=path.join(sdk,'build-tools',tools[0]),aapt=path.join(home,process.platform==='win32'?'aapt2.exe':'aapt2'),signer=path.join(home,'lib/apksigner.jar');
 function run(exe,argv){const r=spawnSync(exe,argv,{encoding:'utf8',maxBuffer:10*1024*1024,windowsHide:true});if(r.status!==0||r.error)throw new Error('Android verification tool failed; inspect bounded local build evidence');return r.stdout;}
 run(path.join(home,process.platform==='win32'?'zipalign.exe':'zipalign'),['-c','-P','16','-v','4',apk]);
 const proof={...parseAndroidProof(run(aapt,['dump','badging',apk]),run(path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'),['-jar',signer,'verify','--verbose','--print-certs',apk])),zip16KBAligned:true};
 run(process.env.PYTHON||'python',[path.resolve(__dirname,'../ci/verify_android_package.py'),apk,'--origin',args.origin,'--output',output]);const report=JSON.parse(fs.readFileSync(output,'utf8'));Object.assign(report,proof,{sourceSnapshotSha256:snapshot});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');console.log(`ANDROID_RELEASE_VERIFY PASS package=${proof.packageName} versionCode=${proof.versionCode} signing=verified release=non-debuggable sourceSnapshot=${snapshot}`);
}
if(require.main===module){try{main();}catch(error){console.error(error.message);process.exitCode=1;}}module.exports={parseAndroidProof};

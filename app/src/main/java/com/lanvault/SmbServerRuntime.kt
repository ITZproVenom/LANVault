package com.lanvault
import android.content.Context
import android.os.Build
import com.lanvault.net.EventLog
import com.lanvault.settings.Settings
import com.lanvault.storage.LocalStorageVolume
import com.lanvault.storage.StorageRepository
import com.lanvault.web.NetworkAddresses
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
class SmbServerRuntime private constructor(context:Context){
 private val app=context.applicationContext;private val root=File(app.filesDir,"lanvault-smb");private val binDir=File(root,"bin");private val config=File(root,"config.json");private val statusFile=File(root,"status.json");private val log=File(root,"smb.log");private val lock=Any()
 @Volatile private var process:Process?=null;@Volatile private var fingerprint:String?=null;@Volatile private var enabled=true;@Volatile private var error=""
 fun sync(settings:Settings,repository:StorageRepository):JSONObject=synchronized(lock){
  enabled=settings.shareSmb
  if(!enabled){stopLocked();return@synchronized state("SMB server disabled in Settings.")}
  val shares=shares(settings,repository)
  if(shares.length()==0){stopLocked();error="No readable directly mounted volumes available. Grant storage access and reconnect the USB drive.";EventLog.shared.warn(error);return@synchronized state(error)}
  val body=JSONObject().put("Port",445).put("FallbackPort",1445).put("StatusPath",statusFile.absolutePath).put("ServerName","LANVAULT").put("RequireAuth",settings.requireAuth).put("Username",settings.username).put("Password",settings.password).put("Shares",shares)
  val hash=sha(body.toString());if(process?.isAlive==true&&fingerprint==hash)return@synchronized state();stopLocked()
  if(!binDir.exists()&&!binDir.mkdirs()){error="Cannot create private SMB directory.";return@synchronized state(error)}
  val asset=assetPath();if(asset==null||!hasAsset(asset)){error="No bundled SMB2/3 executable for this TV ABI.";return@synchronized state(error)}
  val binary=File(binDir,"lanvault-smb")
  try{
   val version=File(binDir,".version").takeIf{it.isFile}?.readText()?.trim()
   if(!binary.isFile||version!="v0.1.0-android"){install(asset,binary);File(binDir,".version").writeText("v0.1.0-android")}
   if(!binary.canExecute()&&!binary.setExecutable(true,true))throw IllegalStateException("Android denied executable permission.")
   runCatching{statusFile.delete()};config.writeText(body.toString(),Charsets.UTF_8);config.setReadable(false,false);config.setReadable(true,true);config.setWritable(false,false);config.setWritable(true,true);runCatching{log.delete()}
   val child=ProcessBuilder(binary.absolutePath,"--config",config.absolutePath).directory(root).redirectErrorStream(true).redirectOutput(log).start()
   process=child;fingerprint=hash;error=""
   if(child.waitFor(1200,TimeUnit.MILLISECONDS)){process=null;error="SMB server exited at startup. "+tail();EventLog.shared.error(error);return@synchronized state(error)}
   for(i in 0 until 8){if(statusFile.isFile)break;Thread.sleep(100)}
   val result=state();runCatching{config.delete()}
   if(result.optBoolean("running"))EventLog.shared.info("SMB2/3 started on port "+result.optInt("port"))else{error=result.optString("message").ifBlank{"SMB starting."};EventLog.shared.warn(error)}
   result
  }catch(e:Throwable){error="SMB launch failed: "+(e.message?:e.javaClass.simpleName);EventLog.shared.error(error);stopLocked();state(error)}
 }
 fun status():JSONObject=synchronized(lock){state()}
 fun stop():JSONObject=synchronized(lock){stopLocked();state("SMB server stopped.")}
 private fun stopLocked(){val p=process;if(p!=null&&p.isAlive){runCatching{p.destroy()};val done=runCatching{p.waitFor(1800,TimeUnit.MILLISECONDS)}.getOrDefault(false);if(!done&&p.isAlive)runCatching{p.destroyForcibly()}};process=null;fingerprint=null}
 private fun shares(settings:Settings,repo:StorageRepository):JSONArray{val out=JSONArray();val used=HashSet<String>();repo.discover().forEach{v->if(!v.readable||v.volume !is LocalStorageVolume)return@forEach;val dir=File(v.mountPath).absoluteFile;if(!dir.isDirectory||!dir.canRead())return@forEach;val base=if(v.kind.name=="INTERNAL")"TVStorage" else "USB_"+safe(v.id.removePrefix("usb_"));var name=safe(base);var n=2;while(!used.add(name.lowercase())){name=safe(base)+"_"+n++};val wr=v.writable&&settings.allowUploads;out.put(JSONObject().put("Name",name).put("Root",dir.absolutePath).put("AllowUploads",wr).put("AllowDeletes",wr&&settings.allowDeletes).put("AllowCreateFolders",wr&&settings.allowCreateFolders).put("ShowHiddenFiles",settings.showHiddenFiles))};return out}
 private fun state(override:String?=null):JSONObject{val alive=process?.isAlive==true;val raw=runCatching{if(statusFile.isFile)JSONObject(statusFile.readText(Charsets.UTF_8))else JSONObject()}.getOrDefault(JSONObject());val running=alive&&raw.optBoolean("Running",false);val p=if(running)raw.optInt("Port",0)else 0;val names=raw.optJSONArray("Shares")?:JSONArray();val urls=JSONArray(if(running&&p>0)NetworkAddresses.ipv4().map{addr(it,p)}else emptyList());val msg=override?:when{!enabled->"SMB disabled in Settings.";running&&p==445->"SMB2/3 listening on port 445.";running&&p==1445->"SMB using fallback port 1445. Native iPhone Files may require port 445.";alive->raw.optString("Message").ifBlank{"SMB starting."};error.isNotBlank()->error;else->raw.optString("Message").ifBlank{"SMB not started."}};return JSONObject().put("enabled",enabled).put("binaryBundled",assetPath()?.let{hasAsset(it)}?:false).put("running",running).put("starting",alive&&!running).put("port",p).put("shareNames",names).put("addresses",urls).put("message",msg).put("logTail",tail())}
 private fun safe(v:String)=v.filter{it.isLetterOrDigit()||it=='_'||it=='-'}.take(40).ifBlank{"Storage"}.let{if(it.equals("IPC",true))"Storage" else it}
 private fun install(asset:String,dest:File){val tmp=File(dest.parentFile,"smb.tmp");app.assets.open(asset).use{i->tmp.outputStream().use{o->i.copyTo(o)}};if(!tmp.setExecutable(true,true)){tmp.delete();throw IllegalStateException("SMB executable permission denied")};if(dest.exists()&&!dest.delete()){tmp.delete();throw IllegalStateException("Cannot replace SMB binary")};if(!tmp.renameTo(dest)){tmp.delete();throw IllegalStateException("Cannot install SMB binary")}}
 private fun assetPath():String?=when{Build.SUPPORTED_ABIS.contains("arm64-v8a")->"smb/lanvault-smb-android-arm64";Build.SUPPORTED_ABIS.any{it=="armeabi-v7a"||it=="armeabi"}->"smb/lanvault-smb-android-arm";else->null}
 private fun hasAsset(p:String)=runCatching{app.assets.open(p).use{it.read()};true}.getOrDefault(false)
 private fun tail()=runCatching{if(!log.isFile)return@runCatching "";log.readLines(Charsets.UTF_8).takeLast(20).joinToString("\n").takeLast(1800)}.getOrDefault("")
 private fun sha(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 companion object{@Volatile private var instance:SmbServerRuntime?=null;fun get(c:Context):SmbServerRuntime=instance?:synchronized(this){instance?:SmbServerRuntime(c.applicationContext).also{instance=it}};fun addr(ip:String,p:Int)="smb://"+ip+if(p==445)"" else ":"+p}
}

package android.util;
import java.io.*;
import java.nio.file.*;
/** Test-only minimal android.util.AtomicFile shadow for JVM boundary testing. */
public final class AtomicFile {
 private final File base;
 private final File tmp;
 public AtomicFile(File baseName){
  this.base=baseName;
  this.tmp=new File(baseName.getPath()+".new");
 }
 public FileInputStream openRead()throws FileNotFoundException{
  return new FileInputStream(base);
 }
 public FileOutputStream startWrite()throws IOException{
  File parent=base.getParentFile();
  if(parent!=null)parent.mkdirs();
  return new FileOutputStream(tmp);
 }
 public void finishWrite(FileOutputStream str){
  try{if(str!=null)str.close();}catch(IOException ignore){}
  try{Files.move(tmp.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignore){}
 }
 public void failWrite(FileOutputStream str){
  try{if(str!=null)str.close();}catch(IOException ignore){}
  tmp.delete();
 }
 public void delete(){
  base.delete();
  tmp.delete();
 }
}

package android.system;
/** Test-only minimal android.system.StructStat shadow for JVM boundary testing. */
public final class StructStat {
 public final int st_mode;
 public final long st_size;
 public StructStat(int st_mode,long st_size){
  this.st_mode=st_mode;
  this.st_size=st_size;
 }
}

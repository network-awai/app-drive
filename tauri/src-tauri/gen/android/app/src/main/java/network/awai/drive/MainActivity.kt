package network.awai.drive
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
class MainActivity: TauriActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val content=findViewById<android.view.View>(android.R.id.content)
  ViewCompat.setOnApplyWindowInsetsListener(content){view,insets->
   val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
   // Keep inputs above the on-screen keyboard as well (AWAI-3393).
   val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
   view.setPadding(bars.left,bars.top,bars.right,max(bars.bottom,ime.bottom))
   insets
  }
  applySystemBarAppearance(resources.configuration)
  ViewCompat.requestApplyInsets(content)
 }
 // uiMode is in the manifest's configChanges, so a theme switch arrives here
 // instead of recreating the activity.
 override fun onConfigurationChanged(newConfig:Configuration){
  super.onConfigurationChanged(newConfig)
  applySystemBarAppearance(newConfig)
 }
 // The bars are drawn over the decor view. Paint it with the page background, which
 // follows the system theme through awai-ui (--awai-background #ffffff / #14110f),
 // with dark icons in light mode and light icons in dark mode (AWAI-3393).
 private fun applySystemBarAppearance(config:Configuration){
  val light=(config.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES
  window.decorView.setBackgroundColor(if(light)0xFFFFFFFF.toInt() else 0xFF14110F.toInt())
  // The 3-button navigation scrim is chosen when the activity starts and does not
  // follow a live theme switch; without it the decor colour shows through.
  if(android.os.Build.VERSION.SDK_INT>=29)window.isNavigationBarContrastEnforced=false
  WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light}
 }
}

package local.ae2addons.cleanroom040;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/** No Minecraft client types are referenced by this loading plugin. */
@IFMLLoadingPlugin.Name("Cleanroom040ServerCompatibility")
@IFMLLoadingPlugin.TransformerExclusions("local.ae2addons.cleanroom040")
public final class ServerCompatibilityPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() {
        return new String[]{"local.ae2addons.cleanroom040.ServerCompatibilityTransformer"};
    }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public String getAccessTransformerClass() { return null; }
    public void injectData(Map<String, Object> data) { }
}

package local.ae2addons.cleanroom040;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * On the exact 0.4.0 dedicated server, prevent Loader from initializing Kirino's
 * renderer-only entry point. The official 0.4.0 client entry references Minecraft
 * during class initialization; configuration cannot prevent that linkage.
 */
public final class ServerCompatibilityTransformer implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !"net.minecraftforge.fml.common.Loader".equals(transformedName)) return bytes;
        if (!FMLLaunchHandler.side().isServer() || !"0.4.0".equals(loaderVersion())) return bytes;
        return guardClientIdentification(bytes);
    }

    private static String loaderVersion() {
        try {
            Class<?> version = Class.forName("com.cleanroommc.common.CleanroomVersion", false,
                ServerCompatibilityTransformer.class.getClassLoader());
            return (String) version.getMethod("getVersion").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot determine Cleanroom version before server compatibility patch", exception);
        }
    }

    static byte[] guardClientIdentification(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, 0);
        int replaced = 0;
        for (var method : owner.methods) {
            if (!method.name.equals("identifyMods")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("com/cleanroommc/kirino/KirinoCore")
                    && call.name.equals("identifyMods") && call.desc.equals("(Ljava/util/List;)V")) {
                    // Consume the list argument. Renderer discovery has no dedicated-server work.
                    method.instructions.set(call, new InsnNode(Opcodes.POP));
                    replaced++;
                }
            }
        }
        if (replaced != 1) {
            throw new IllegalStateException("Expected exactly one Cleanroom 0.4.0 client discovery call, found " + replaced);
        }
        ClassWriter output = new ClassWriter(0);
        owner.accept(output);
        System.out.println("[Cleanroom040ServerCompatibility] Isolated Kirino client discovery on Cleanroom 0.4.0 dedicated server");
        return output.toByteArray();
    }
}

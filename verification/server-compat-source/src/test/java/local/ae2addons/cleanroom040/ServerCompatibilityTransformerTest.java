package local.ae2addons.cleanroom040;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class ServerCompatibilityTransformerTest {
    private static byte[] loader(boolean hasRendererCall) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraftforge/fml/common/Loader", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PRIVATE, "identifyMods", "()V", null, null);
        method.visitCode();
        if (hasRendererCall) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/cleanroommc/kirino/KirinoCore", "identifyMods", "(Ljava/util/List;)V", false);
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "example/Discovery", "discover", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void removesOnlyClientDiscoveryAndKeepsStackBalanced() {
        byte[] patched = ServerCompatibilityTransformer.guardClientIdentification(loader(true));
        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        var instructions = node.methods.getFirst().instructions.toArray();
        assertEquals(4, instructions.length);
        assertEquals(Opcodes.ACONST_NULL, instructions[0].getOpcode());
        assertEquals(Opcodes.POP, instructions[1].getOpcode());
        MethodInsnNode remaining = assertInstanceOf(MethodInsnNode.class, instructions[2]);
        assertEquals("example/Discovery", remaining.owner);
        assertEquals("discover", remaining.name);
        assertEquals(Opcodes.RETURN, instructions[3].getOpcode());
    }

    @Test
    void rejectsUnexpectedLoaderLayout() {
        assertThrows(IllegalStateException.class, () -> ServerCompatibilityTransformer.guardClientIdentification(loader(false)));
    }
}

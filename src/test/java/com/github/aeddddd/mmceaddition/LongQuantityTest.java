package com.github.aeddddd.mmceaddition;

import com.github.aeddddd.mmceaddition.util.*;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class LongQuantityTest {
    @BeforeAll static void bootstrap() throws Exception {
        if (FMLCommonHandler.instance().getSidedDelegate()==null) {
            var side=Proxy.newProxyInstance(IFMLSidedHandler.class.getClassLoader(),new Class[]{IFMLSidedHandler.class},
                    (proxy,method,args) -> method.getName().equals("getSide") ? Side.SERVER : null);
            var field=FMLCommonHandler.class.getDeclaredField("sidedDelegate");
            field.setAccessible(true); field.set(FMLCommonHandler.instance(),side);
        }
        Bootstrap.register();
    }
    private static ItemVariant apple() { return new ItemVariant(new ItemStack(Items.APPLE)); }
    @Test void nativeLongItemsRoundTripWithoutNarrowing() {
        var buffer=new LongItemBuffer(); long amount=6_000_000_000L;
        assertEquals(amount,buffer.insertLong(apple(),amount));
        NBTTagCompound tag=new NBTTagCompound(); buffer.writeToNBT(tag);
        var saved=tag.copy();
        var copy=new LongItemBuffer(); copy.readFromNBT(tag);
        assertEquals(saved,tag,"Reading must not rewrite the saved long Count");
        assertEquals(amount,copy.getAmount(apple()));
    }
    @Test void fullLongBufferReturnsOnlyRemainingCapacity() {
        var buffer=new LongItemBuffer(); buffer.insertLong(apple(),Long.MAX_VALUE-2);
        assertEquals(2,buffer.insertLong(apple(),10));
        assertEquals(0,buffer.insertLong(apple(),1));
        assertEquals(Long.MAX_VALUE,buffer.getAmount(apple()));
    }
    @Test void visibleInputDecrementPreservesHiddenLongRemainder() {
        var buffer=new LongItemBuffer(); buffer.insertLong(apple(),6_000_000_000L);
        var handler=new LongInputItemHandler(buffer); handler.syncWithBuffer();
        var visible=handler.getStackInSlot(0).copy(); visible.shrink(1); handler.setStackInSlot(0,visible);
        assertEquals(5_999_999_999L,buffer.getAmount(apple()));
        handler.setStackInSlot(0,ItemStack.EMPTY);
        assertEquals(5_999_999_999L-Integer.MAX_VALUE,buffer.getAmount(apple()));
        assertFalse(handler.getStackInSlot(0).isEmpty());
    }
    @Test void inputSimulationCopyCannotConsumeTheRealBuffer() {
        var buffer=new LongItemBuffer(); buffer.insertLong(apple(),5_000_000_000L);
        var handler=new LongInputItemHandler(buffer); handler.syncWithBuffer();
        var copy=handler.copy(); copy.setStackInSlot(0,ItemStack.EMPTY);
        assertEquals(5_000_000_000L,buffer.getAmount(apple()));
        assertEquals(Integer.MAX_VALUE,copy.getStackInSlot(0).getCount());
    }
    @Test void longFluidUnitsRemainMillibucketsAcrossNbt() {
        var buffer=new LongFluidBuffer(); long amount=3_000_000_000L;
        assertEquals(amount,buffer.fillLong(FluidRegistry.WATER,amount));
        NBTTagCompound tag=new NBTTagCompound(); buffer.writeToNBT(tag);
        var copy=new LongFluidBuffer(); copy.readFromNBT(tag);
        assertEquals(amount,copy.getAmount(FluidRegistry.WATER));
        assertEquals(Long.MAX_VALUE-amount,copy.fillLong(FluidRegistry.WATER,Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE,copy.getAmount(FluidRegistry.WATER));
    }
}

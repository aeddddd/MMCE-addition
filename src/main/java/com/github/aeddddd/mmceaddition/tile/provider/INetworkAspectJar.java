package com.github.aeddddd.mmceaddition.tile.provider;

import appeng.api.networking.security.IActionHost;

/**
 * 网络源质代理 jar 的标记接口（不含任何 Thaumcraft 类型引用）.
 *
 * <p>用途：MixinAspectProviderCopy 需要识别"该 jar 是否为网络代理"，
 * 但 mixin 类的方法签名已含 TC 类型，若再直接引用 NetworkAspectJar 具体类，
 * 在 TC 缺失时会导致 mixin 类加载失败。通过本标记接口做 instanceof 判定，
 * 使引用链上不出现 NetworkAspectJar 的符号。</p>
 */
public interface INetworkAspectJar {

    /**
     * @return 网络宿主（总成方块），用于以 IActionHost 身份访问 AE 网络
     */
    IActionHost getNetworkHost();
}

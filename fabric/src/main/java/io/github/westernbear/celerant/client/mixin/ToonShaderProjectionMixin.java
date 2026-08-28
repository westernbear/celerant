package io.github.westernbear.celerant.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public abstract class ToonShaderProjectionMixin {
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
	private GpuBufferSlice celerant$captureToonProjection(ProjectionMatrixBuffer buffer, Matrix4f projection) {
		com.modularmods.mcgltf.ToonShader.captureProjection(projection);
		return buffer.getBuffer(projection);
	}
}

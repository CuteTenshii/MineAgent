package moe.tenshii.mineagent.game.task;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Optionally turns toward a block, then captures one frame of the player's view with the HUD hidden.
 */
public final class LookTask implements Task {
	public static final String MEDIA_TYPE = "image/jpeg";
	private static final int MAX_WIDTH = 1024;
	private static final float JPEG_QUALITY = 0.85F;
	private static final float FACING_TOLERANCE = 2.0F;
	private static final int MAX_TURN_TICKS = 20;
	// Frames rendered after hiding the HUD before capturing, so the capture shows the change.
	private static final int SETTLE_TICKS = 2;

	private final @Nullable BlockPos target;
	private int turnTicks;
	private boolean hidHud;
	private int settleTicks;
	private boolean requested;
	private volatile @Nullable BufferedImage captured;
	private volatile @Nullable String captureError;
	private @Nullable String caption;

	public LookTask(@Nullable BlockPos target) {
		this.target = target == null ? null : target.immutable();
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (target != null && turnTicks < MAX_TURN_TICKS && !PlayerControl.isFacing(player, PlayerControl.center(target), FACING_TOLERANCE)) {
			turnTicks++;
			PlayerControl.lookAt(player, PlayerControl.center(target));
			return null;
		}
		if (!requested) {
			if (!hidHud && !mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
				hidHud = true;
			}
			if (++settleTicks <= SETTLE_TICKS) {
				return null;
			}
			requested = true;
			caption = describeView(mc, player);
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
				try (image) {
					BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
					for (int y = 0; y < image.getHeight(); y++) {
						for (int x = 0; x < image.getWidth(); x++) {
							copy.setRGB(x, y, image.getPixel(x, y));
						}
					}
					captured = copy;
				} catch (RuntimeException e) {
					captureError = e.toString();
				}
			});
			return null;
		}
		if (captureError != null) {
			throw new TaskFailure("Could not capture the screen: " + captureError);
		}
		if (captured == null) {
			return null;
		}
		restoreHud(mc);
		return caption;
	}

	private static String describeView(Minecraft mc, LocalPlayer player) {
		StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
			"Screenshot of the player's view from %s, facing %s (yaw %.0f, pitch %.0f; negative pitch looks up), HUD hidden.",
			BlockPos.containing(player.getEyePosition()).toShortString(), player.getDirection().getName(), player.getYRot(), player.getXRot()));
		HitResult hit = player.pick(64.0, 1.0F, false);
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			Vec3 eye = player.getEyePosition();
			text.append(String.format(Locale.ROOT, " The center of the image is %s at %s, %.1f blocks away.",
				WorldInfo.blockName(mc.level, blockHit.getBlockPos()), blockHit.getBlockPos().toShortString(), eye.distanceTo(hit.getLocation())));
		} else {
			text.append(" Nothing solid is at the center of the image within 64 blocks.");
		}
		return text.toString();
	}

	/** The captured frame, scaled to at most {@value #MAX_WIDTH} pixels wide and encoded as base64 JPEG. */
	public String encodedImage() throws TaskFailure {
		BufferedImage source = captured;
		if (source == null) {
			throw new TaskFailure("No screenshot was captured.");
		}
		BufferedImage scaled = source;
		if (source.getWidth() > MAX_WIDTH) {
			int height = Math.max(1, Math.round(source.getHeight() * (MAX_WIDTH / (float) source.getWidth())));
			scaled = new BufferedImage(MAX_WIDTH, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = scaled.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, MAX_WIDTH, height, null);
			graphics.dispose();
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			ImageWriteParam params = writer.getDefaultWriteParam();
			params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			params.setCompressionQuality(JPEG_QUALITY);
			writer.setOutput(output);
			writer.write(null, new IIOImage(scaled, null, null), params);
		} catch (IOException e) {
			throw new TaskFailure("Could not encode the screenshot: " + e.getMessage());
		} finally {
			writer.dispose();
		}
		return Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	private void restoreHud(Minecraft mc) {
		if (hidHud) {
			hidHud = false;
			if (mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		}
	}

	@Override
	public void stop(Minecraft mc) {
		restoreHud(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 10;
	}

	@Override
	public String describe() {
		return "taking a screenshot";
	}
}

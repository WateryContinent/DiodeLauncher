package net.minecraft.launcher.skins;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A tiny software 3D renderer for Minecraft player skins, so the launcher doesn't need OpenGL.
 *
 * <p>The player is built out of boxes like the in-game model (head, body, arms, legs, the outer
 * hat/jacket/sleeve/pants layer and the cape). Every pixel of the skin becomes one small flat-coloured
 * square on a box face, and the squares are drawn with a depth buffer, which keeps the blocky look
 * exactly. Model units are skin pixels: the player is 32 tall, facing +z, with feet at y = 0.</p>
 */
public final class SkinRenderer {
   private static final int SUPERSAMPLE = 2;
   private static final double ARM_ANGLE = Math.toRadians(5.0);
   private static final double CAPE_ANGLE = Math.toRadians(10.0);

   private SkinRenderer() {
   }

   /** A skin (and optional cape) turned into flat-coloured squares, ready to render from any angle. */
   public static final class Model {
      private final List<float[]> quads;

      private Model(List<float[]> quads) {
         this.quads = quads;
      }
   }

   /**
    * Builds the 3D model.
    *
    * @param skin a 64x64 skin, or an old-style 64x32 one
    * @param slim whether the skin uses the slim (Alex, 3 pixel) arms
    * @param cape a 64x32 cape picture, or null for no cape
    */
   public static Model build(BufferedImage skin, boolean slim, BufferedImage cape) {
      List<float[]> quads = new ArrayList<>();
      boolean legacy = skin.getHeight() < 64;
      int armWidth = slim ? 3 : 4;
      Transform none = new Transform();
      Transform rightArm = new Transform().rotateZ(-ARM_ANGLE, -4 - armWidth / 2.0f, 22, 0);
      Transform leftArm = new Transform().rotateZ(ARM_ANGLE, 4 + armWidth / 2.0f, 22, 0);

      // Base layer (always solid, like in game)
      box(quads, skin, 0, 0, 8, 8, 8, -4, 24, -4, 0, false, false, none);                       // head
      box(quads, skin, 16, 16, 8, 12, 4, -4, 12, -2, 0, false, false, none);                    // body
      box(quads, skin, 40, 16, armWidth, 12, 4, -4 - armWidth, 12, -2, 0, false, false, rightArm);
      box(quads, skin, 0, 16, 4, 12, 4, -4, 0, -2, 0, false, false, none);                      // right leg
      if (legacy) {
         // Old 64x32 skins only have a right arm and leg; the game mirrors them for the left side.
         box(quads, skin, 40, 16, armWidth, 12, 4, -4 - armWidth, 12, -2, 0, false, true, leftArm);
         box(quads, skin, 0, 16, 4, 12, 4, -4, 0, -2, 0, false, true, none);
      } else {
         box(quads, skin, 32, 48, armWidth, 12, 4, 4, 12, -2, 0, false, false, leftArm);
         box(quads, skin, 16, 48, 4, 12, 4, 0, 0, -2, 0, false, false, none);
      }

      // Outer layer (see-through where the skin is transparent)
      if (!legacy || !isFullyOpaque(skin, 32, 0, 32, 16)) {
         box(quads, skin, 32, 0, 8, 8, 8, -4, 24, -4, 0.5f, true, false, none);                 // hat
      }
      if (!legacy) {
         box(quads, skin, 16, 32, 8, 12, 4, -4, 12, -2, 0.25f, true, false, none);              // jacket
         box(quads, skin, 40, 32, armWidth, 12, 4, -4 - armWidth, 12, -2, 0.25f, true, false, rightArm);
         box(quads, skin, 48, 48, armWidth, 12, 4, 4, 12, -2, 0.25f, true, false, leftArm);
         box(quads, skin, 0, 32, 4, 12, 4, -4, 0, -2, 0.25f, true, false, none);                // right pants
         box(quads, skin, 0, 48, 4, 12, 4, 0, 0, -2, 0.25f, true, false, none);                 // left pants
      }

      if (cape != null) {
         // Capes are 64x32 pictures (or a multiple of that for HD capes).
         int capeScale = Math.max(1, cape.getWidth() / 64);
         BufferedImage capeTexture = capeScale == 1 ? cape : downscale(cape, capeScale);
         // Built facing forwards, then turned around to hang behind the player, tilted out a little.
         Transform capeTransform = new Transform().rotateY180().rotateX(CAPE_ANGLE, 0, 24, -2);
         box(quads, capeTexture, 0, 0, 10, 16, 1, -5, 8, 2, 0, false, false, capeTransform);
      }

      return new Model(quads);
   }

   /**
    * Draws the model on a transparent picture.
    *
    * @param yawDegrees how far the player is turned (0 = facing the viewer, 180 = back)
    * @param pitchDegrees how far above the player the viewer is
    */
   public static BufferedImage render(Model model, double yawDegrees, double pitchDegrees, int width, int height) {
      int w = Math.max(1, width * SUPERSAMPLE);
      int h = Math.max(1, height * SUPERSAMPLE);
      int[] colour = new int[w * h];
      float[] depth = new float[w * h];
      Arrays.fill(depth, Float.NEGATIVE_INFINITY);

      double yaw = Math.toRadians(yawDegrees);
      double pitch = Math.toRadians(pitchDegrees);
      float cosY = (float)Math.cos(yaw);
      float sinY = (float)Math.sin(yaw);
      float cosP = (float)Math.cos(pitch);
      float sinP = (float)Math.sin(pitch);
      // The model (with hat and cape) fits in roughly 20 x 34 pixels; leave a little room around it.
      float scale = (float)Math.min(w / 22.0, h / 36.0);
      float centreX = w / 2.0f;
      float centreY = h / 2.0f;
      // Light from the upper left, in front of the viewer.
      float lightX = -0.45f;
      float lightY = 0.65f;
      float lightZ = 0.6f;
      float lightLength = (float)Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
      lightX /= lightLength;
      lightY /= lightLength;
      lightZ /= lightLength;

      float[] sx = new float[4];
      float[] sy = new float[4];
      float[] sz = new float[4];
      for (float[] quad : model.quads) {
         // quad = 4 corners (x, y, z) then the normal (x, y, z) then the colour
         float nx = quad[12] * cosY + quad[14] * sinY;
         float nz1 = -quad[12] * sinY + quad[14] * cosY;
         float ny = quad[13] * cosP - nz1 * sinP;
         float nz = quad[13] * sinP + nz1 * cosP;
         if (nz <= 0.0001f) {
            continue; // facing away
         }

         for (int corner = 0; corner < 4; corner++) {
            float x = quad[corner * 3];
            float y = quad[corner * 3 + 1] - 16.0f;
            float z = quad[corner * 3 + 2];
            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            sx[corner] = centreX + x1 * scale;
            sy[corner] = centreY - (y * cosP - z1 * sinP) * scale;
            sz[corner] = y * sinP + z1 * cosP;
         }

         float light = 0.55f + 0.45f * Math.max(0.0f, nx * lightX + ny * lightY + nz * lightZ);
         int rgb = Float.floatToRawIntBits(quad[15]);
         int shaded = 0xFF000000
            | Math.min(255, (int)(((rgb >> 16) & 0xFF) * light)) << 16
            | Math.min(255, (int)(((rgb >> 8) & 0xFF) * light)) << 8
            | Math.min(255, (int)((rgb & 0xFF) * light));
         triangle(colour, depth, w, h, sx[0], sy[0], sz[0], sx[1], sy[1], sz[1], sx[2], sy[2], sz[2], shaded);
         triangle(colour, depth, w, h, sx[0], sy[0], sz[0], sx[2], sy[2], sz[2], sx[3], sy[3], sz[3], shaded);
      }

      return average(colour, w, h, width, height);
   }

   /** Guesses whether a 64x64 skin was made for slim arms: those leave the 4th arm column empty. */
   public static boolean looksSlim(BufferedImage skin) {
      return skin.getWidth() == 64 && skin.getHeight() == 64 && (skin.getRGB(54, 20) >>> 24) == 0 && (skin.getRGB(54, 31) >>> 24) == 0;
   }

   private static void box(
      List<float[]> out, BufferedImage texture, int u, int v, int w, int h, int d,
      float x, float y, float z, float inflate, boolean overlay, boolean mirror, Transform transform
   ) {
      float x0 = x - inflate;
      float x1 = x + w + inflate;
      float y0 = y - inflate;
      float y1 = y + h + inflate;
      float z0 = z - inflate;
      float z1 = z + d + inflate;
      //   texture area               top-left corner      top-right corner     bottom-left corner   normal
      face(out, texture, u + d, v, w, d,             x0, y1, z0, x1, y1, z0, x0, y1, z1, 0, 1, 0, overlay, mirror, transform);  // top
      face(out, texture, u + d + w, v, w, d,         x0, y0, z1, x1, y0, z1, x0, y0, z0, 0, -1, 0, overlay, mirror, transform); // bottom
      face(out, texture, u, v + d, d, h,             x0, y1, z0, x0, y1, z1, x0, y0, z0, -1, 0, 0, overlay, mirror, transform); // right side
      face(out, texture, u + d, v + d, w, h,         x0, y1, z1, x1, y1, z1, x0, y0, z1, 0, 0, 1, overlay, mirror, transform);  // front
      face(out, texture, u + d + w, v + d, d, h,     x1, y1, z1, x1, y1, z0, x1, y0, z1, 1, 0, 0, overlay, mirror, transform);  // left side
      face(out, texture, u + d + w + d, v + d, w, h, x1, y1, z0, x0, y1, z0, x1, y0, z0, 0, 0, -1, overlay, mirror, transform); // back
   }

   private static void face(
      List<float[]> out, BufferedImage texture, int u, int v, int tw, int th,
      float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
      float nx, float ny, float nz, boolean overlay, boolean mirror, Transform transform
   ) {
      // a = top-left, b = top-right, c = bottom-left of the face as it's drawn in the skin file
      float ux = (bx - ax) / tw;
      float uy = (by - ay) / tw;
      float uz = (bz - az) / tw;
      float vx = (cx - ax) / th;
      float vy = (cy - ay) / th;
      float vz = (cz - az) / th;
      for (int j = 0; j < th; j++) {
         for (int i = 0; i < tw; i++) {
            int px = u + i;
            int py = v + j;
            if (px < 0 || py < 0 || px >= texture.getWidth() || py >= texture.getHeight()) {
               continue;
            }

            int argb = texture.getRGB(px, py);
            if (overlay && (argb >>> 24) < 26) {
               continue; // transparent part of the outer layer
            }

            float[] quad = new float[16];
            corner(quad, 0, ax + ux * i + vx * j, ay + uy * i + vy * j, az + uz * i + vz * j);
            corner(quad, 1, ax + ux * (i + 1) + vx * j, ay + uy * (i + 1) + vy * j, az + uz * (i + 1) + vz * j);
            corner(quad, 2, ax + ux * (i + 1) + vx * (j + 1), ay + uy * (i + 1) + vy * (j + 1), az + uz * (i + 1) + vz * (j + 1));
            corner(quad, 3, ax + ux * i + vx * (j + 1), ay + uy * i + vy * (j + 1), az + uz * i + vz * (j + 1));
            quad[12] = nx;
            quad[13] = ny;
            quad[14] = nz;
            if (mirror) {
               for (int k = 0; k < 4; k++) {
                  quad[k * 3] = -quad[k * 3];
               }
               quad[12] = -quad[12];
            }

            transform.apply(quad);
            quad[15] = Float.intBitsToFloat(argb & 0xFFFFFF);
            out.add(quad);
         }
      }
   }

   private static void corner(float[] quad, int index, float x, float y, float z) {
      quad[index * 3] = x;
      quad[index * 3 + 1] = y;
      quad[index * 3 + 2] = z;
   }

   private static void triangle(
      int[] colour, float[] depth, int w, int h,
      float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, int argb
   ) {
      float area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
      if (Math.abs(area) < 1.0E-6f) {
         return;
      }

      int minX = Math.max(0, (int)Math.floor(Math.min(x0, Math.min(x1, x2))));
      int maxX = Math.min(w - 1, (int)Math.ceil(Math.max(x0, Math.max(x1, x2))));
      int minY = Math.max(0, (int)Math.floor(Math.min(y0, Math.min(y1, y2))));
      int maxY = Math.min(h - 1, (int)Math.ceil(Math.max(y0, Math.max(y1, y2))));
      float inverseArea = 1.0f / area;
      // A hair of overlap so neighbouring squares never leave a gap between them.
      float tolerance = -0.002f;
      for (int py = minY; py <= maxY; py++) {
         float cy = py + 0.5f;
         for (int px = minX; px <= maxX; px++) {
            float cx = px + 0.5f;
            float b0 = ((x2 - x1) * (cy - y1) - (y2 - y1) * (cx - x1)) * inverseArea;
            float b1 = ((x0 - x2) * (cy - y2) - (y0 - y2) * (cx - x2)) * inverseArea;
            float b2 = 1.0f - b0 - b1;
            if (b0 < tolerance || b1 < tolerance || b2 < tolerance) {
               continue;
            }

            float z = b0 * z0 + b1 * z1 + b2 * z2;
            int index = py * w + px;
            if (z > depth[index]) {
               depth[index] = z;
               colour[index] = argb;
            }
         }
      }
   }

   /** Shrinks the supersampled picture back down, averaging for smooth edges. */
   private static BufferedImage average(int[] colour, int w, int h, int width, int height) {
      BufferedImage result = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            int a = 0;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int dy = 0; dy < SUPERSAMPLE; dy++) {
               for (int dx = 0; dx < SUPERSAMPLE; dx++) {
                  int sx = x * SUPERSAMPLE + dx;
                  int sy = y * SUPERSAMPLE + dy;
                  if (sx >= w || sy >= h) {
                     continue;
                  }

                  int argb = colour[sy * w + sx];
                  int alpha = argb >>> 24;
                  a += alpha;
                  r += ((argb >> 16) & 0xFF) * alpha;
                  g += ((argb >> 8) & 0xFF) * alpha;
                  b += (argb & 0xFF) * alpha;
               }
            }

            if (a > 0) {
               int samples = SUPERSAMPLE * SUPERSAMPLE;
               result.setRGB(x, y, (a / samples) << 24 | (r / a) << 16 | (g / a) << 8 | (b / a));
            }
         }
      }

      return result;
   }

   private static boolean isFullyOpaque(BufferedImage image, int x, int y, int w, int h) {
      for (int py = y; py < Math.min(image.getHeight(), y + h); py++) {
         for (int px = x; px < Math.min(image.getWidth(), x + w); px++) {
            if ((image.getRGB(px, py) >>> 24) < 128) {
               return false;
            }
         }
      }

      return true;
   }

   private static BufferedImage downscale(BufferedImage image, int factor) {
      BufferedImage result = new BufferedImage(image.getWidth() / factor, image.getHeight() / factor, BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < result.getHeight(); y++) {
         for (int x = 0; x < result.getWidth(); x++) {
            result.setRGB(x, y, image.getRGB(x * factor, y * factor));
         }
      }

      return result;
   }

   /** Rotations applied to a part's corners and normal after it's built (arm angles, the cape's hang). */
   private static final class Transform {
      private final List<float[]> steps = new ArrayList<>();

      Transform rotateZ(double angle, float px, float py, float pz) {
         this.steps.add(new float[]{2, (float)Math.cos(angle), (float)Math.sin(angle), px, py, pz});
         return this;
      }

      Transform rotateX(double angle, float px, float py, float pz) {
         this.steps.add(new float[]{1, (float)Math.cos(angle), (float)Math.sin(angle), px, py, pz});
         return this;
      }

      Transform rotateY180() {
         this.steps.add(new float[]{3, 0, 0, 0, 0, 0});
         return this;
      }

      void apply(float[] quad) {
         for (float[] step : this.steps) {
            for (int point = 0; point < 5; point++) {
               // points 0-3 are corners; point 4 is the normal, which turns but doesn't move
               int base = point == 4 ? 12 : point * 3;
               float px = point == 4 ? 0 : step[3];
               float py = point == 4 ? 0 : step[4];
               float pz = point == 4 ? 0 : step[5];
               float x = quad[base] - px;
               float y = quad[base + 1] - py;
               float z = quad[base + 2] - pz;
               float cos = step[1];
               float sin = step[2];
               if (step[0] == 1) {
                  float ny = y * cos - z * sin;
                  z = y * sin + z * cos;
                  y = ny;
               } else if (step[0] == 2) {
                  float nx = x * cos - y * sin;
                  y = x * sin + y * cos;
                  x = nx;
               } else {
                  x = -x;
                  z = -z;
               }

               quad[base] = x + px;
               quad[base + 1] = y + py;
               quad[base + 2] = z + pz;
            }
         }
      }
   }
}

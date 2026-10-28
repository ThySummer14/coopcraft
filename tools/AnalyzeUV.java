import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Picks usable texture sample points for a vanilla tool sprite.
 *
 * <p>The weapon meshes sample the item sprite through normalised UVs, so every
 * face needs a UV that actually lands on an opaque pixel — otherwise the cutout
 * render type discards it. This prints the centroid of the wooden pixels and of
 * the dominant metal colour, in UV space.
 *
 * <p>Usage: AnalyzeUV &lt;minecraft-client.jar&gt; diamond_axe diamond_pickaxe ...
 */
public final class AnalyzeUV {
    public static void main(String[] args) throws Exception {
        try (ZipFile zip = new ZipFile(new File(args[0]))) {
            for (int i = 1; i < args.length; i++) {
                String name = args[i];
                ZipEntry entry = zip.getEntry("assets/minecraft/textures/item/" + name + ".png");
                if (entry == null) {
                    System.out.println(name + ": texture not found");
                    continue;
                }
                BufferedImage img;
                try (InputStream in = zip.getInputStream(entry)) {
                    img = ImageIO.read(in);
                }
                report(name, img);
            }
        }
    }

    private static void report(String name, BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();

        double woodU = 0, woodV = 0;
        int woodN = 0;
        Map<Integer, Integer> counts = new HashMap<>();
        Map<Integer, double[]> sums = new HashMap<>();
        java.util.List<double[]> metalPx = new java.util.ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < 200) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;

                double u = (x + 0.5) / w;
                double v = (y + 0.5) / h;

                // 木头：明显偏暖（红 > 蓝）
                if (r > g && g >= b && r - b > 45) {
                    woodU += u;
                    woodV += v;
                    woodN++;
                } else {
                    metalPx.add(new double[] {u, v, 0.299 * r + 0.587 * g + 0.114 * b});
                    int key = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
                    counts.merge(key, 1, Integer::sum);
                    double[] acc = sums.computeIfAbsent(key, k -> new double[3]);
                    acc[0] += u;
                    acc[1] += v;
                    acc[2] += 1;
                }
            }
        }

        System.out.printf("%s (%dx%d) wood=", name, w, h);
        if (woodN > 0) {
            System.out.printf("%.3f,%.3f (%d px)", woodU / woodN, woodV / woodN, woodN);
        } else {
            System.out.print("none");
        }

        int best = -1, bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best >= 0) {
            double[] acc = sums.get(best);
            System.out.printf("  metal=%.3f,%.3f (%d px)", acc[0] / acc[2], acc[1] / acc[2], bestCount);
        }
        // 亮金属：按亮度排序取最亮的三成，质心落在高光面上而不是描边
        if (!metalPx.isEmpty()) {
            metalPx.sort((a, b) -> Double.compare(b[2], a[2]));
            int take = Math.max(1, metalPx.size() * 3 / 10);
            double bu = 0, bv = 0;
            for (int k = 0; k < take; k++) {
                bu += metalPx.get(k)[0];
                bv += metalPx.get(k)[1];
            }
            System.out.printf("  bright=%.3f,%.3f (%d px)", bu / take, bv / take, take);
        }
        System.out.println();
        // 前 8 个色桶，带真实 RGB，用来人工挑"这一把工具的主体色"
        counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(8)
            .forEach(e -> {
                int k = e.getKey();
                double[] acc = sums.get(k);
                System.out.printf("    rgb(%d,%d,%d) uv=%.3f,%.3f n=%d%n",
                    (k >> 8) * 17, ((k >> 4) & 15) * 17, (k & 15) * 17,
                    acc[0] / acc[2], acc[1] / acc[2], e.getValue());
            });
    }
}

package forge.screens.match.vfx;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs an OpenGL 3.3 render loop on a dedicated thread.
 * Particles are rendered off-screen into an FBO; each frame is read back
 * via double-buffered PBOs and handed to VFXLayer as a BufferedImage.
 *
 * Thread model:
 *   - All GL calls happen on the GL thread (the Thread this class creates).
 *   - VFXLayer (EDT) reads only the published BufferedImage via displayIndex.
 *   - ParticleSystem uses a ConcurrentLinkedQueue for cross-thread handoff.
 */
public final class VFXRenderer {

    // ---- GLSL shaders ----

    private static final String VERT_SRC =
        "#version 330 core\n" +
        "layout(location=0) in vec2 aPos;\n" +
        "layout(location=1) in vec4 aColor;\n" +
        "layout(location=2) in float aSize;\n" +
        "out vec4 vColor;\n" +
        "uniform vec2 uScreen;\n" +
        "void main() {\n" +
        "    vec2 ndc = vec2(aPos.x/uScreen.x*2.0-1.0, 1.0-aPos.y/uScreen.y*2.0);\n" +
        "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
        "    gl_PointSize = aSize;\n" +
        "    vColor = aColor;\n" +
        "}\n";

    private static final String FRAG_SRC =
        "#version 330 core\n" +
        "in vec4 vColor;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 c = gl_PointCoord - 0.5;\n" +
        "    float d = length(c);\n" +
        "    if (d > 0.5) discard;\n" +
        "    float alpha = 1.0 - smoothstep(0.25, 0.5, d);\n" +
        "    fragColor = vec4(vColor.rgb, vColor.a * alpha);\n" +
        "}\n";

    // floats per particle in the VBO: x, y, r, g, b, a, size
    private static final int FLOATS_PER_PARTICLE = 7;
    private static final int TARGET_FPS = 60;
    private static final long FRAME_NANOS = 1_000_000_000L / TARGET_FPS;

    // ---- shared state ----

    private final ParticleSystem particles;
    private final AtomicReference<VFXLayer> layerRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ping-pong BufferedImages; displayIndex is the one the EDT may read
    private final BufferedImage[] images = new BufferedImage[2];
    private final AtomicInteger displayIndex = new AtomicInteger(0);

    private volatile int width;
    private volatile int height;
    private volatile boolean sizeChanged = false;

    private Thread glThread;

    // ---- construction ----

    public VFXRenderer(final ParticleSystem particles, final int initialWidth, final int initialHeight) {
        this.particles = particles;
        this.width  = Math.max(1, initialWidth);
        this.height = Math.max(1, initialHeight);
    }

    // ---- public API (called from EDT or VFXController) ----

    public void setLayer(final VFXLayer layer) {
        layerRef.set(layer);
    }

    public void resize(final int w, final int h) {
        final int nw = Math.max(1, w);
        final int nh = Math.max(1, h);
        if (nw != width || nh != height) {
            width  = nw;
            height = nh;
            sizeChanged = true;
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) { return; }
        glThread = new Thread(this::renderLoop, "VFX-GL");
        glThread.setDaemon(true);
        glThread.start();
    }

    void stop() {
        running.set(false);
        if (glThread != null) {
            try { glThread.join(2000); } catch (final InterruptedException ignored) { Thread.currentThread().interrupt(); }
            glThread = null;
        }
    }

    /** Returns the BufferedImage that was most recently completed by the GL thread. May be null. */
    BufferedImage getDisplayImage() {
        return images[displayIndex.get()];
    }

    // ---- GL render loop ----

    private void renderLoop() {
        // GLFW + context init
        if (!GLFW.glfwInit()) { running.set(false); return; }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,               GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,        GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE); // macOS

        final long window = GLFW.glfwCreateWindow(1, 1, "VFX-offscreen", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) { GLFW.glfwTerminate(); running.set(false); return; }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        // GL objects
        final int program = buildProgram();
        if (program == 0) { cleanup(window); return; }

        final int uScreen = GL20.glGetUniformLocation(program, "uScreen");

        // VAO + VBO
        final int vao = GL30.glGenVertexArrays();
        final int vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // aPos   (location 0): 2 floats, stride = FLOATS_PER_PARTICLE * 4 bytes
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, FLOATS_PER_PARTICLE * Float.BYTES, 0L);
        // aColor (location 1): 4 floats, offset 2*4
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, FLOATS_PER_PARTICLE * Float.BYTES, 2L * Float.BYTES);
        // aSize  (location 2): 1 float, offset 6*4
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, FLOATS_PER_PARTICLE * Float.BYTES, 6L * Float.BYTES);

        GL30.glBindVertexArray(0);

        // FBO state holders
        int[] fbo    = {0};
        int[] fboTex = {0};
        int[] pbo    = {0, 0};
        int curW = 0, curH = 0;

        // host-side pixel array for bulk copy
        int[] pixelBuf = new int[0];
        // which PBO did we start the async readback into last frame?
        int readPBO = 0;
        boolean firstFrame = true;

        // CPU-side float buffer for VBO upload (pre-allocated, grows as needed)
        FloatBuffer vboCpu = MemoryUtil.memAllocFloat(ParticleSystem.MAX_PARTICLES * FLOATS_PER_PARTICLE);

        GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE); // allow gl_PointSize in vertex shader
        GL11.glEnable(GL11.GL_BLEND);
        // additive glow: RGB adds (GL_ONE dest), alpha accumulates
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);

        GL20.glUseProgram(program);

        long lastTime = System.nanoTime();

        while (running.get()) {
            final long frameStart = System.nanoTime();
            final float dt = Math.min((frameStart - lastTime) / 1_000_000_000f, 0.05f);
            lastTime = frameStart;

            // handle resize
            final int w = width;
            final int h = height;
            if (w != curW || h != curH || sizeChanged) {
                sizeChanged = false;
                curW = w; curH = h;

                // delete old FBO objects
                if (fbo[0] != 0)    { GL30.glDeleteFramebuffers(fbo[0]);  fbo[0] = 0; }
                if (fboTex[0] != 0) { GL11.glDeleteTextures(fboTex[0]);   fboTex[0] = 0; }
                if (pbo[0] != 0)    { GL15.glDeleteBuffers(pbo[0]);        pbo[0] = 0; }
                if (pbo[1] != 0)    { GL15.glDeleteBuffers(pbo[1]);        pbo[1] = 0; }

                // create FBO texture (RGBA8, curW×curH)
                fboTex[0] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, fboTex[0]);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                        curW, curH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

                // create FBO
                fbo[0] = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[0]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, fboTex[0], 0);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

                // create two PBOs sized for BGRA readback (4 bytes per pixel)
                final int pboBytes = curW * curH * 4;
                for (int i = 0; i < 2; i++) {
                    pbo[i] = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pbo[i]);
                    GL15.glBufferData(GL21_PIXEL_PACK_BUFFER, pboBytes, GL15.GL_STREAM_READ);
                }
                GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);

                pixelBuf = new int[curW * curH];

                // recreate both ping-pong BufferedImages at the new size
                images[0] = new BufferedImage(curW, curH, BufferedImage.TYPE_INT_ARGB);
                images[1] = new BufferedImage(curW, curH, BufferedImage.TYPE_INT_ARGB);

                firstFrame = true;
                readPBO = 0;
            }

            if (curW == 0 || curH == 0) { sleepRemainder(frameStart); continue; }

            // update particle simulation
            particles.update(dt);

            // ---- render particles into FBO ----
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[0]);
            GL11.glViewport(0, 0, curW, curH);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            final List<Particle> active = particles.getParticles();
            final int count = active.size();

            if (count > 0) {
                // build VBO data
                if (vboCpu.capacity() < count * FLOATS_PER_PARTICLE) {
                    MemoryUtil.memFree(vboCpu);
                    vboCpu = MemoryUtil.memAllocFloat(count * FLOATS_PER_PARTICLE);
                }
                vboCpu.clear();
                for (int i = 0; i < count; i++) {
                    final Particle p = active.get(i);
                    final float lifeRatio = p.lifeRatio();
                    vboCpu.put(p.x).put(p.y)
                          .put(p.r).put(p.g).put(p.b).put(p.a * lifeRatio)
                          .put(p.size * (0.5f + 0.5f * lifeRatio)); // shrink as they die
                }
                vboCpu.flip();

                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) count * FLOATS_PER_PARTICLE * Float.BYTES, GL15.GL_STREAM_DRAW);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vboCpu);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

                GL20.glUniform2f(uScreen, curW, curH);
                GL30.glBindVertexArray(vao);
                GL11.glDrawArrays(GL11.GL_POINTS, 0, count);
                GL30.glBindVertexArray(0);
            }

            // ---- start async PBO readback for THIS frame ----
            // bind pbo[readPBO] as pack target, kick off async read
            GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pbo[readPBO]);
            GL11.glReadPixels(0, 0, curW, curH, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            // ---- map PREVIOUS frame's PBO and publish ----
            if (!firstFrame) {
                final int prevPBO = 1 - readPBO;
                GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pbo[prevPBO]);
                final ByteBuffer mapped = GL15.glMapBuffer(GL21_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
                if (mapped != null) {
                    final IntBuffer ib = mapped.asIntBuffer();
                    // write into the non-displayed buffer
                    final int writeIdx = 1 - displayIndex.get();
                    final BufferedImage dst = images[writeIdx];
                    if (dst != null && dst.getWidth() == curW && dst.getHeight() == curH) {
                        ib.get(pixelBuf, 0, curW * curH);
                        dst.getRaster().setDataElements(0, 0, curW, curH, pixelBuf);
                        // flip Y: OpenGL origin is bottom-left, Swing is top-left
                        flipImageVertically(dst, curW, curH, pixelBuf);
                        // publish
                        displayIndex.set(writeIdx);
                        final VFXLayer layer = layerRef.get();
                        if (layer != null) {
                            layer.repaintFromGL();
                        }
                    }
                    GL15.glUnmapBuffer(GL21_PIXEL_PACK_BUFFER);
                }
                GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);
            }

            firstFrame = false;
            readPBO = 1 - readPBO;

            sleepRemainder(frameStart);
        }

        // cleanup
        MemoryUtil.memFree(vboCpu);
        if (fbo[0] != 0)    { GL30.glDeleteFramebuffers(fbo[0]); }
        if (fboTex[0] != 0) { GL11.glDeleteTextures(fboTex[0]); }
        if (pbo[0] != 0)    { GL15.glDeleteBuffers(pbo[0]); }
        if (pbo[1] != 0)    { GL15.glDeleteBuffers(pbo[1]); }
        GL15.glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteProgram(program);
        cleanup(window);
    }

    // GL_PIXEL_PACK_BUFFER token (GL 2.1+, available in GL 3.3 core)
    private static final int GL21_PIXEL_PACK_BUFFER = 0x88EB;

    // ---- helpers ----

    private static void flipImageVertically(final BufferedImage img,
            final int w, final int h, final int[] scratch) {
        final int[] raster = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int top = 0, bot = h - 1; top < bot; top++, bot--) {
            final int topOff = top * w;
            final int botOff = bot * w;
            System.arraycopy(raster, topOff, scratch, 0, w);
            System.arraycopy(raster, botOff, raster,  topOff, w);
            System.arraycopy(scratch, 0,     raster,  botOff, w);
        }
    }

    private static void sleepRemainder(final long frameStart) {
        final long elapsed = System.nanoTime() - frameStart;
        final long remaining = FRAME_NANOS - elapsed;
        if (remaining > 1_000_000L) {
            try { Thread.sleep(remaining / 1_000_000L); }
            catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void cleanup(final long window) {
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private static int buildProgram() {
        final int vert = compileShader(GL20.GL_VERTEX_SHADER,   VERT_SRC);
        final int frag = compileShader(GL20.GL_FRAGMENT_SHADER, FRAG_SRC);
        if (vert == 0 || frag == 0) {
            if (vert != 0) { GL20.glDeleteShader(vert); }
            if (frag != 0) { GL20.glDeleteShader(frag); }
            return 0;
        }
        final int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vert);
        GL20.glAttachShader(prog, frag);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int compileShader(final int type, final String src) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}

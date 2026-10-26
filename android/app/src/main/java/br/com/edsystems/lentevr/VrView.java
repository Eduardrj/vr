package br.com.edsystems.lentevr;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Desenha a imagem capturada lado a lado, com a pré-distorção de lente do modelo Google Cardboard.
 * Toda a geometria é em milímetros reais da tela, a partir do centro físico do display.
 */
class VrView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private static final String TAG = "LenteVR";

    private static final String VS =
            "attribute vec2 a;\n" +
            "void main(){gl_Position=vec4(a,0.,1.);}";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uTexM;\n" +
            "uniform vec2 uCentro;\n" +
            "uniform float uMPerPx,uLensSep,uSL,uK1,uK2,uMaxT,uHW,uIA,uDepth,uModo;\n" +
            "vec4 cor(vec2 uv){return texture2D(uTex,(uTexM*vec4(uv,0.,1.)).xy);}\n" +
            "void main(){\n" +
            "  vec2 px=gl_FragCoord.xy;\n" +
            "  float olho=px.x<uCentro.x?-1.:1.;\n" +
            "  vec2 m=(px-uCentro)*uMPerPx;\n" +
            "  vec2 d=(m-vec2(olho*uLensSep*.5,0.))/uSL;\n" +
            "  float rd2=dot(d,d);\n" +
            "  float g=1.+uK1*rd2+uK2*rd2*rd2;\n" +
            "  vec2 t=d*g;\n" +
            "  if(sqrt(rd2)*g>uMaxT){gl_FragColor=vec4(0.,0.,0.,1.);return;}\n" +
            "  vec2 uv=vec2(t.x/uHW,t.y/(uHW/uIA))*.5+.5;\n" +
            "  if(uv.x<0.||uv.x>1.||uv.y<0.||uv.y>1.){gl_FragColor=vec4(0.,0.,0.,1.);return;}\n" +
            "  if(uModo>.5&&uModo<1.5){\n" +
            "    float l=dot(cor(uv).rgb,vec3(.299,.587,.114));\n" +
            "    uv.x=clamp(uv.x+olho*uDepth*(l-.5)*.05,0.,1.);\n" +
            "  }\n" +
            "  if(uModo>1.5){uv.x=uv.x*.5+(olho>0.?.5:0.);}\n" +
            "  gl_FragColor=vec4(cor(uv).rgb,1.);\n" +
            "}";

    private final SurfaceTexture textura;
    private final WindowManager wm;
    private final float[] texM = new float[16];
    private final FloatBuffer quad;

    private volatile Ajustes ajustes;
    private volatile int capW = 16, capH = 9;
    private volatile float centroX, centroY;   // centro físico da tela, em px da view (origem no topo)
    private volatile float metrosPorPx;
    private volatile float telaWm, telaHm;     // tamanho físico do display (metros), na orientação atual

    private int prog, texId;
    private int uTex, uTexM, uCentro, uMPerPx, uLensSep, uSL, uK1, uK2, uMaxT, uHW, uIA, uDepth, uModo;
    private int vistaW = 1, vistaH = 1;

    VrView(Context c, SurfaceTexture textura, Ajustes ajustes) {
        super(c);
        this.textura = textura;
        this.ajustes = ajustes;
        this.wm = c.getSystemService(WindowManager.class);

        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        textura.setOnFrameAvailableListener(st -> requestRender(), new Handler(Looper.getMainLooper()));
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> atualizarGeometria());
    }

    void setAjustes(Ajustes a) { ajustes = a; requestRender(); }

    void setTamanhoCaptura(int w, int h) {
        if (w > 0 && h > 0) { capW = w; capH = h; requestRender(); }
    }

    /** Calcula onde fica o centro físico da tela dentro desta view e quantos metros mede cada pixel. */
    void atualizarGeometria() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        Rect tela = wm.getMaximumWindowMetrics().getBounds();
        centroX = tela.width() / 2f - loc[0];
        centroY = tela.height() / 2f - loc[1];

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float dpi = (dm.xdpi + dm.ydpi) / 2f;
        double diagPx = Math.hypot(tela.width(), tela.height());
        double diagPol = diagPx / dpi;
        if (dpi < 100 || diagPol < 4.0 || diagPol > 8.0) {
            // valor físico suspeito: usa a tela do Galaxy A14 (6,6")
            dpi = (float) (diagPx / 6.6);
        }
        metrosPorPx = 0.0254f / dpi;
        telaWm = tela.width() * metrosPorPx;
        telaHm = tela.height() * metrosPorPx;
        requestRender();
    }

    // ---------- Renderer ----------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        prog = programa(VS, FS);
        int a = GLES20.glGetAttribLocation(prog, "a");
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uTexM = GLES20.glGetUniformLocation(prog, "uTexM");
        uCentro = GLES20.glGetUniformLocation(prog, "uCentro");
        uMPerPx = GLES20.glGetUniformLocation(prog, "uMPerPx");
        uLensSep = GLES20.glGetUniformLocation(prog, "uLensSep");
        uSL = GLES20.glGetUniformLocation(prog, "uSL");
        uK1 = GLES20.glGetUniformLocation(prog, "uK1");
        uK2 = GLES20.glGetUniformLocation(prog, "uK2");
        uMaxT = GLES20.glGetUniformLocation(prog, "uMaxT");
        uHW = GLES20.glGetUniformLocation(prog, "uHW");
        uIA = GLES20.glGetUniformLocation(prog, "uIA");
        uDepth = GLES20.glGetUniformLocation(prog, "uDepth");
        uModo = GLES20.glGetUniformLocation(prog, "uModo");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texId = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        try { textura.detachFromGLContext(); } catch (RuntimeException ignorado) { }
        try {
            textura.attachToGLContext(texId);
        } catch (RuntimeException e) {
            Log.e(TAG, "attachToGLContext falhou", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        vistaW = Math.max(1, w);
        vistaH = Math.max(1, h);
        GLES20.glViewport(0, 0, vistaW, vistaH);
        post(this::atualizarGeometria);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            textura.updateTexImage();
            textura.getTransformMatrix(texM);
        } catch (RuntimeException e) {
            Log.w(TAG, "updateTexImage", e);
        }
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (vistaW < 8 || metrosPorPx <= 0) return; // oculto (1x1) ou sem geometria ainda

        Ajustes aj = ajustes;
        float sep = aj.lentesMm / 1000f;
        float sl = Ajustes.TELA_LENTE_M;
        float k1 = Ajustes.K1, k2 = Ajustes.K2;

        // limites visíveis de cada lente, em tangente do ângulo percebido
        float interno = distort((sep / 2f) / sl, k1, k2);
        float externo = distort((telaWm / 2f - sep / 2f) / sl, k1, k2);
        float vertical = distort((telaHm / 2f) / sl, k1, k2);
        float maxT = (float) Math.tan(Math.toRadians(Ajustes.FOV_MAX_GRAUS));

        float ia = (aj.modo == 2 ? capW / 2f : capW) / (float) capH;
        float hw = Math.min(Math.min(interno, externo), Math.min(maxT, vertical * ia)) * 0.96f * aj.zoom;

        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uTexM, 1, false, texM, 0);
        GLES20.glUniform2f(uCentro, centroX, vistaH - centroY);
        GLES20.glUniform1f(uMPerPx, metrosPorPx);
        GLES20.glUniform1f(uLensSep, sep);
        GLES20.glUniform1f(uSL, sl);
        GLES20.glUniform1f(uK1, k1);
        GLES20.glUniform1f(uK2, k2);
        GLES20.glUniform1f(uMaxT, Math.min(maxT, Math.max(interno, Math.max(externo, vertical))));
        GLES20.glUniform1f(uHW, hw);
        GLES20.glUniform1f(uIA, ia);
        GLES20.glUniform1f(uDepth, aj.profundidade);
        GLES20.glUniform1f(uModo, aj.modo);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static float distort(float r, float k1, float k2) {
        float r2 = r * r;
        return r * (1f + k1 * r2 + k2 * r2 * r2);
    }

    private static int shader(int tipo, String src) {
        int s = GLES20.glCreateShader(tipo);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int programa(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}

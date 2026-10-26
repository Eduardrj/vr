package br.com.edsystems.lentevr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Captura um único app escolhido pelo usuário e mostra a imagem em VR numa sobreposição.
 * A captura de um app só não inclui as nossas sobreposições, então não há efeito espelho.
 */
public class VrService extends Service {

    static final String ACAO_INICIAR = "iniciar";
    static final String ACAO_PARAR = "parar";
    static final String EXTRA_CODIGO = "codigo";
    static final String EXTRA_DADOS = "dados";
    private static final String CANAL = "vr";

    static volatile boolean rodando = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MediaProjection projecao;
    private MediaProjection.Callback callback;
    private VirtualDisplay tela;
    private SurfaceTexture textura;
    private Surface superficie;
    private VrView vr;
    private WindowManager.LayoutParams lpVr;
    private TextView botao;
    private int dpi;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Lente VR ativo", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String acao = intent != null ? intent.getAction() : null;
        if (ACAO_PARAR.equals(acao) || intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // No Android 14+ o serviço precisa estar em primeiro plano antes de pegar a projeção.
        startForeground(1, notificacao(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int codigo = intent.getIntExtra(EXTRA_CODIGO, 0);
        Intent dados = intent.getParcelableExtra(EXTRA_DADOS, Intent.class);
        if (dados == null) { stopSelf(); return START_NOT_STICKY; }

        encerrarCaptura();
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projecao = mpm.getMediaProjection(codigo, dados);
        } catch (RuntimeException e) {
            projecao = null;
        }
        if (projecao == null) {
            Toast.makeText(this, "Não foi possível iniciar a captura.", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        callback = new MediaProjection.Callback() {
            @Override public void onStop() { h.post(VrService.this::stopSelf); }
            @Override public void onCapturedContentResize(int w, int hh) { h.post(() -> redimensionar(w, hh)); }
        };
        projecao.registerCallback(callback, h);

        Rect r = wm.getMaximumWindowMetrics().getBounds();
        dpi = getResources().getDisplayMetrics().densityDpi;
        textura = new SurfaceTexture(false);
        textura.setDefaultBufferSize(r.width(), r.height());
        superficie = new Surface(textura);
        tela = projecao.createVirtualDisplay("LenteVR", r.width(), r.height(), dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, superficie, null, h);

        criarSobreposicoes(r.width(), r.height());
        rodando = true;
        Toast.makeText(this, "Abra o vídeo em tela cheia e toque no botão VR.", Toast.LENGTH_LONG).show();
        return START_NOT_STICKY;
    }

    private void redimensionar(int w, int hh) {
        if (w <= 0 || hh <= 0 || textura == null || tela == null) return;
        textura.setDefaultBufferSize(w, hh);
        tela.resize(w, hh, dpi);
        if (vr != null) vr.setTamanhoCaptura(w, hh);
    }

    // ---------- sobreposições ----------

    private void criarSobreposicoes(int capW, int capH) {
        vr = new VrView(this, textura, Ajustes.ler(this));
        vr.setTamanhoCaptura(capW, capH);
        lpVr = new WindowManager.LayoutParams();
        lpVr.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lpVr.format = PixelFormat.OPAQUE;
        lpVr.gravity = Gravity.TOP | Gravity.START;
        lpVr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lpVr.setFitInsetsTypes(0);
        aplicarModoVr(false);
        wm.addView(vr, lpVr);

        GestureDetector toques = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { mostrarVr(false); return true; }
        });
        vr.setOnTouchListener((v, e) -> toques.onTouchEvent(e));

        botao = new TextView(this);
        botao.setText("VR");
        botao.setTextColor(Color.parseColor("#1C1206"));
        botao.setTypeface(Typeface.DEFAULT_BOLD);
        botao.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        botao.setGravity(Gravity.CENTER);
        GradientDrawable fundo = new GradientDrawable();
        fundo.setShape(GradientDrawable.OVAL);
        fundo.setColor(Color.parseColor("#FFA24D"));
        fundo.setStroke(dp(2), Color.parseColor("#0F1218"));
        botao.setBackground(fundo);
        botao.setElevation(dp(6));
        botao.setOnClickListener(v -> mostrarVr(true));
        botao.setOnLongClickListener(v -> { stopSelf(); return true; });

        WindowManager.LayoutParams lpB = new WindowManager.LayoutParams(dp(60), dp(60),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lpB.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpB.x = dp(10);
        wm.addView(botao, lpB);
    }

    private void aplicarModoVr(boolean visivel) {
        if (visivel) {
            lpVr.width = WindowManager.LayoutParams.MATCH_PARENT;
            lpVr.height = WindowManager.LayoutParams.MATCH_PARENT;
            lpVr.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            lpVr.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else {
            // escondido: 1x1 pixel, sem receber toques, mas mantendo o OpenGL vivo
            lpVr.width = 1;
            lpVr.height = 1;
            lpVr.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lpVr.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private void mostrarVr(boolean visivel) {
        if (vr == null) return;
        if (visivel) vr.setAjustes(Ajustes.ler(this));
        aplicarModoVr(visivel);
        wm.updateViewLayout(vr, lpVr);
        if (botao != null) botao.setVisibility(visivel ? View.GONE : View.VISIBLE);
        h.postDelayed(() -> { if (vr != null) vr.atualizarGeometria(); }, 300);
    }

    // ---------- notificação e limpeza ----------

    private Notification notificacao() {
        PendingIntent parar = PendingIntent.getService(this, 1,
                new Intent(this, VrService.class).setAction(ACAO_PARAR), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent abrir = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_lente)
                .setContentTitle("Lente VR ativo")
                .setContentText("Toque no botão VR. Toque duas vezes na tela para sair do VR.")
                .setContentIntent(abrir)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Parar", parar).build())
                .build();
    }

    private void encerrarCaptura() {
        if (vr != null) { try { wm.removeView(vr); } catch (RuntimeException ignorado) { } vr = null; }
        if (botao != null) { try { wm.removeView(botao); } catch (RuntimeException ignorado) { } botao = null; }
        if (tela != null) { tela.release(); tela = null; }
        if (projecao != null) {
            if (callback != null) projecao.unregisterCallback(callback);
            projecao.stop();
            projecao = null;
        }
        if (superficie != null) { superficie.release(); superficie = null; }
        if (textura != null) { textura.release(); textura = null; }
    }

    @Override
    public void onDestroy() {
        rodando = false;
        encerrarCaptura();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}

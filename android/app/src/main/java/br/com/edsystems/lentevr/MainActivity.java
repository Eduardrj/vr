package br.com.edsystems.lentevr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURA = 10;
    private static final int REQ_NOTIF = 11;

    private static final int COR_FUNDO = Color.parseColor("#0F1218");
    private static final int COR_TEXTO = Color.parseColor("#E9EDF5");
    private static final int COR_SUAVE = Color.parseColor("#93A0B6");
    private static final int COR_DESTAQUE = Color.parseColor("#FFA24D");

    private Ajustes ajustes;
    private Button btnSobrepor;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ajustes = Ajustes.ler(this);
        getWindow().setStatusBarColor(COR_FUNDO);
        getWindow().setNavigationBarColor(COR_FUNDO);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COR_FUNDO);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, dp(28), p, dp(40));
        scroll.addView(col);

        TextView titulo = texto("Lente VR", 30, COR_TEXTO);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(titulo);
        col.addView(texto("Transforma o app que está na tela (YouTube, galeria, qualquer um) em VR para o visor Shinecon.", 15, COR_SUAVE));

        status = texto("", 14, COR_DESTAQUE);
        status.setPadding(0, dp(12), 0, 0);
        col.addView(status);

        col.addView(secao("1. Permissão"));
        btnSobrepor = botao("Permitir mostrar sobre outros apps", false);
        btnSobrepor.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        col.addView(btnSobrepor);

        col.addView(secao("2. Tipo de imagem"));
        RadioGroup grupo = new RadioGroup(this);
        String[] modos = {"Cinema (vídeo normal em tela grande)", "3D simulado (profundidade pelo brilho)", "Já é 3D lado a lado"};
        for (int i = 0; i < modos.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(100 + i);
            r.setText(modos[i]);
            r.setTextColor(COR_TEXTO);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            r.setPadding(dp(4), dp(10), 0, dp(10));
            grupo.addView(r);
        }
        grupo.check(100 + ajustes.modo);
        grupo.setOnCheckedChangeListener((g, id) -> { ajustes.modo = id - 100; ajustes.salvar(this); });
        col.addView(grupo);

        col.addView(secao("3. Ajustes (já calibrados para o seu A14)"));
        final TextView rotLentes = texto("", 15, COR_TEXTO);
        col.addView(rotLentes);
        SeekBar sbLentes = new SeekBar(this);
        sbLentes.setMax(60); // 40..70 mm em passos de 0,5
        sbLentes.setProgress(Math.round((ajustes.lentesMm - 40f) * 2f));
        col.addView(sbLentes);
        Runnable atualizaLentes = () -> rotLentes.setText(String.format(Locale.ROOT, "Distância entre as imagens: %.1f mm (padrão 55)", ajustes.lentesMm).replace('.', ','));
        atualizaLentes.run();
        sbLentes.setOnSeekBarChangeListener(new Mudou(v -> { ajustes.lentesMm = 40f + v / 2f; atualizaLentes.run(); }));

        final TextView rotZoom = texto("", 15, COR_TEXTO);
        rotZoom.setPadding(0, dp(12), 0, 0);
        col.addView(rotZoom);
        SeekBar sbZoom = new SeekBar(this);
        sbZoom.setMax(70); // 60..130 %
        sbZoom.setProgress(Math.round(ajustes.zoom * 100f) - 60);
        col.addView(sbZoom);
        Runnable atualizaZoom = () -> rotZoom.setText("Tamanho da imagem: " + Math.round(ajustes.zoom * 100f) + "%");
        atualizaZoom.run();
        sbZoom.setOnSeekBarChangeListener(new Mudou(v -> { ajustes.zoom = (60 + v) / 100f; atualizaZoom.run(); }));

        col.addView(secao("4. Começar"));
        col.addView(texto(
                "• Toque em Iniciar.\n" +
                "• Na janela do Android, escolha \"Um único app\" e depois o YouTube (ou outro app). Não escolha a tela inteira.\n" +
                "• No YouTube, coloque o vídeo em tela cheia, na horizontal.\n" +
                "• Toque no botão laranja VR que fica na lateral da tela e coloque o celular no visor.\n" +
                "• Para sair do VR: toque duas vezes na tela. Para encerrar: segure o botão VR ou use a notificação.", 15, COR_SUAVE));
        Button iniciar = botao("Iniciar", true);
        iniciar.setOnClickListener(v -> iniciar());
        col.addView(iniciar);
        Button parar = botao("Parar", false);
        parar.setOnClickListener(v -> { stopService(new Intent(this, VrService.class)); atualizarStatus(); });
        col.addView(parar);

        setContentView(scroll);

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizarStatus();
    }

    private void atualizarStatus() {
        boolean ok = Settings.canDrawOverlays(this);
        btnSobrepor.setText(ok ? "Permissão concedida ✓" : "Permitir mostrar sobre outros apps");
        btnSobrepor.setEnabled(!ok);
        status.setText(VrService.rodando ? "VR ativo: use o botão laranja VR na lateral da tela." : "");
    }

    private void iniciar() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Primeiro permita mostrar sobre outros apps (passo 1).", Toast.LENGTH_LONG).show();
            return;
        }
        ajustes.salvar(this);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent pedido = mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice());
        startActivityForResult(pedido, REQ_CAPTURA);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURA) return;
        if (res != RESULT_OK || data == null) {
            Toast.makeText(this, "Captura cancelada.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, VrService.class)
                .setAction(VrService.ACAO_INICIAR)
                .putExtra(VrService.EXTRA_CODIGO, res)
                .putExtra(VrService.EXTRA_DADOS, data);
        startForegroundService(svc);
        atualizarStatus();
    }

    // ---------- helpers de interface ----------

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView texto(String s, int sp, int cor) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(cor);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private TextView secao(String s) {
        TextView t = texto(s.toUpperCase(Locale.ROOT), 13, COR_SUAVE);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(26), 0, dp(8));
        return t;
    }

    private Button botao(String s, boolean principal) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        bt.setGravity(Gravity.CENTER);
        if (principal) {
            bt.setBackgroundColor(COR_DESTAQUE);
            bt.setTextColor(Color.parseColor("#1C1206"));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(10);
        bt.setLayoutParams(lp);
        return bt;
    }

    /** Ouvinte simples de SeekBar que salva ao soltar. */
    private class Mudou implements SeekBar.OnSeekBarChangeListener {
        private final java.util.function.IntConsumer acao;
        Mudou(java.util.function.IntConsumer acao) { this.acao = acao; }
        @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) acao.accept(v); }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { ajustes.salvar(MainActivity.this); }
    }
}

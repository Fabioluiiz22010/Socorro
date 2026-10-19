package com.example.tressensores;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;


public class AlertaActivity extends AppCompatActivity implements Estado.Ouvinte {

    // Giroflex: o fundo alterna entre vermelho e azul, com troca seca.
    // 600 ms por cor = menos de 2 trocas por segundo, abaixo do limite de 3 por segundo
    // que pode provocar crises em pessoas com epilepsia fotossensível.
    private static final int VERMELHO = Color.parseColor("#E3212F");
    private static final int AZUL = Color.parseColor("#1D4ED8");
    private static final long TROCA_MS = 600;

    private TextView txtTitulo, txtMotivo, txtSegundos;
    private Button btnCancelar;
    private View fundo;
    private boolean azul = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable giroflex = new Runnable() {
        @Override
        public void run() {
            azul = !azul;
            fundo.setBackgroundColor(azul ? AZUL : VERMELHO);
            handler.postDelayed(this, TROCA_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_alerta);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        fundo = findViewById(R.id.alerta);
        ViewCompat.setOnApplyWindowInsetsListener(fundo, (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        txtTitulo = findViewById(R.id.txtTitulo);
        txtMotivo = findViewById(R.id.txtMotivo);
        txtSegundos = findViewById(R.id.txtSegundos);
        btnCancelar = findViewById(R.id.btnCancelar);

        btnCancelar.setOnClickListener(v -> {
            MonitorService.comando(this, MonitorService.ACAO_CANCELAR);
            finish();
        });
        findViewById(R.id.btnEnviarAgora).setOnClickListener(v -> {
            MonitorService.comando(this, MonitorService.ACAO_ENVIAR_AGORA);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Estado.observar(this);
        onEstadoMudou();
        if (!isFinishing() && animacoesLigadas()) {
            handler.removeCallbacks(giroflex);
            handler.postDelayed(giroflex, TROCA_MS);
        }
    }

    @Override
    protected void onPause() {
        Estado.remover(this);
        handler.removeCallbacks(giroflex);
        azul = false;
        fundo.setBackgroundColor(VERMELHO);
        super.onPause();
    }

    /** Se a pessoa desligou as animações do sistema, a tela fica parada em vermelho. */
    private boolean animacoesLigadas() {
        return Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    @Override
    public void onEstadoMudou() {
        if (!Estado.contagemAtiva) {
            finish();   // cancelado ou já enviado
            return;
        }
        txtTitulo.setText(Estado.contagemTitulo);
        txtMotivo.setText(Estado.contagemMotivo);
        txtSegundos.setText(String.valueOf(Estado.segundosRestantes));
        btnCancelar.setText(Estado.contagemTextoCancelar);
    }
}

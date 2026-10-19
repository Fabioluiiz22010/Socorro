package com.example.tressensores;

import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


public class AlertaActivity extends AppCompatActivity implements Estado.Ouvinte {

    private TextView txtTitulo, txtMotivo, txtSegundos;
    private Button btnCancelar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_alerta);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.alerta), (v, insets) -> {
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
    }

    @Override
    protected void onPause() {
        Estado.remover(this);
        super.onPause();
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

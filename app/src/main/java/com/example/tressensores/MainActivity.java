package com.example.tressensores;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class MainActivity extends AppCompatActivity implements Estado.Ouvinte {

    // Cores (as mesmas de res/values/cores.xml)
    private static final int NAVY = Color.parseColor("#141414");      // texto principal (socorro_texto)
    private static final int NAVY_2 = Color.parseColor("#141414");    // proteção desligada (socorro_preto)
    private static final int VERDE = Color.parseColor("#067647");
    private static final int VERMELHO = Color.parseColor("#E3212F");
    private static final int LARANJA = Color.parseColor("#B45309");
    private static final int AZUL = Color.parseColor("#2F66E0");
    private static final int CINZA = Color.parseColor("#6F6F6F");
    private static final int FUNDO = Color.parseColor("#F4F4F4");     // socorro_fundo
    private static final int BORDA = Color.parseColor("#EBEBEB");     // socorro_borda

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private boolean modoNoturno = false;
    private boolean atualizandoTela = false;
    private String acaoPendente = null;
    private String ultimoHistorico = null;

    // Estrutura
    private View raiz;
    private MaterialToolbar toolbar;
    private BottomNavigationView navegacao;
    private View telaInicio, telaSensores, telaHistorico, telaAjustes;
    private OnBackPressedCallback voltarParaInicio;

    // Início
    private MaterialCardView cardHero;
    private TextView txtStatus, txtStatusDetalhe, txtClimaResumo, txtClima, txtApiResumo, txtApi;
    private MaterialButton btnProtecao;

    // Sensores
    private TextView txtSensoresAviso, valAceleracao, valRotacao, valPosicao, valLuz, valPressao, valGps, txtLocalizacao;

    // Histórico
    private LinearLayout listaHistorico;
    private TextView txtHistoricoVazio;

    // Ajustes
    private TextInputLayout campoUsuario;
    private TextInputEditText edtUsuario, edtTelefone, edtIntervalo;
    private SwitchMaterial switchCheckin;
    private LinearLayout listaPermissoes;

    private final ActivityResultLauncher<String[]> pedirPermissoes = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), resultado -> {
                atualizarPermissoes();
                if (acaoPendente != null && temLocalizacao()) {
                    MonitorService.comando(this, acaoPendente);
                }
                acaoPendente = null;
            });

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("socorro", MODE_PRIVATE);


        if (prefs.getString("usuario", "").trim().isEmpty()) {
            startActivity(new Intent(this, BoasVindasActivity.class));
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        // Cabeçalho roxo atrás da barra de status: ícones claros
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        ligarViews();
        aplicarMargensDoSistema();
        configurarMenus();
        configurarBotoes();
        carregarDados();

        if (savedInstanceState == null && !temLocalizacao()) solicitarPermissoes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (raiz == null) return;
        Estado.telaPrincipalVisivel = true;
        Estado.observar(this);
        ultimoHistorico = null;
        onEstadoMudou();
        atualizarPermissoes();
    }

    @Override
    protected void onPause() {
        if (raiz != null) {
            Estado.telaPrincipalVisivel = false;
            Estado.remover(this);
            salvarDados(false);
        }
        super.onPause();
    }

    private void ligarViews() {
        raiz = findViewById(R.id.main);
        toolbar = findViewById(R.id.toolbar);
        navegacao = findViewById(R.id.navegacao);
        telaInicio = findViewById(R.id.telaInicio);
        telaSensores = findViewById(R.id.telaSensores);
        telaHistorico = findViewById(R.id.telaHistorico);
        telaAjustes = findViewById(R.id.telaAjustes);

        cardHero = findViewById(R.id.cardHero);
        txtStatus = findViewById(R.id.txtStatus);
        txtStatusDetalhe = findViewById(R.id.txtStatusDetalhe);
        btnProtecao = findViewById(R.id.btnProtecao);
        txtClimaResumo = findViewById(R.id.txtClimaResumo);
        txtClima = findViewById(R.id.txtClima);
        txtApiResumo = findViewById(R.id.txtApiResumo);
        txtApi = findViewById(R.id.txtApi);

        txtSensoresAviso = findViewById(R.id.txtSensoresAviso);
        valAceleracao = findViewById(R.id.valAceleracao);
        valRotacao = findViewById(R.id.valRotacao);
        valPosicao = findViewById(R.id.valPosicao);
        valLuz = findViewById(R.id.valLuz);
        valPressao = findViewById(R.id.valPressao);
        valGps = findViewById(R.id.valGps);
        txtLocalizacao = findViewById(R.id.txtLocalizacao);

        listaHistorico = findViewById(R.id.listaHistorico);
        txtHistoricoVazio = findViewById(R.id.txtHistoricoVazio);

        campoUsuario = findViewById(R.id.campoUsuario);
        edtUsuario = findViewById(R.id.edtUsuario);
        edtTelefone = findViewById(R.id.edtTelefone);
        edtIntervalo = findViewById(R.id.edtIntervalo);
        switchCheckin = findViewById(R.id.switchCheckin);
        listaPermissoes = findViewById(R.id.listaPermissoes);
    }

     private void aplicarMargensDoSistema() {
        View barraTopo = findViewById(R.id.barraTopo);
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, 0, b.right, 0);
            barraTopo.setPadding(0, b.top, 0, 0);
            navegacao.setPadding(0, 0, 0, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ------------------------------------------------------------------ menus

    private void configurarMenus() {

        navegacao.setOnItemSelectedListener(item -> {
            mostrarTela(item.getItemId());
            return true;
        });

         voltarParaInicio = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                navegacao.setSelectedItemId(R.id.nav_inicio);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, voltarParaInicio);

        toolbar.inflateMenu(R.menu.menu_topo);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.acao_perfil) {
                navegacao.setSelectedItemId(R.id.nav_ajustes);
                return true;
            } else if (id == R.id.acao_painel) {
                abrirPainel();
                return true;
            } else if (id == R.id.acao_sobre) {
                mostrarSobre();
                return true;
            }
            return false;
        });
    }

    private void mostrarTela(int itemId) {
        telaInicio.setVisibility(itemId == R.id.nav_inicio ? View.VISIBLE : View.GONE);
        telaSensores.setVisibility(itemId == R.id.nav_sensores ? View.VISIBLE : View.GONE);
        telaHistorico.setVisibility(itemId == R.id.nav_historico ? View.VISIBLE : View.GONE);
        telaAjustes.setVisibility(itemId == R.id.nav_ajustes ? View.VISIBLE : View.GONE);
        voltarParaInicio.setEnabled(itemId != R.id.nav_inicio);
        if (itemId == R.id.nav_ajustes) atualizarPermissoes();
    }

    private void mostrarSobre() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Cai Cai Balão")
                .setMessage("Detecção inteligente de quedas e assistência de emergência.\n\n"
                        + "Usa acelerômetro, giroscópio, proximidade, luminosidade e barômetro para "
                        + "detectar quedas, e envia a localização para a API Socorro e por SMS.\n\n"
                        + "API: " + ApiClient.URL_API + "\n"
                        + "Aparelho: " + Build.MANUFACTURER + " " + Build.MODEL)
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void abrirPainel() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ApiClient.URL_API)));
    }


    private void configurarBotoes() {
        //Início
        btnProtecao.setOnClickListener(v -> {
            if (Estado.monitorando) MonitorService.comando(this, MonitorService.ACAO_PARAR);
            else executarNoServico(MonitorService.ACAO_INICIAR);
        });
        findViewById(R.id.btnPanico).setOnClickListener(v -> executarNoServico(MonitorService.ACAO_PANICO));
        findViewById(R.id.btnSimular).setOnClickListener(v -> executarNoServico(MonitorService.ACAO_SIMULAR));
        findViewById(R.id.btnClima).setOnClickListener(v -> verificarClima());

        //Histórico
        findViewById(R.id.btnPainel).setOnClickListener(v -> abrirPainel());
        findViewById(R.id.btnLimpar).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Limpar histórico?")
                .setMessage("Os eventos salvos neste aparelho serão apagados. O painel online não é afetado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar", (d, w) -> Estado.limparHistorico(this))
                .show());

        //Ajustes
        findViewById(R.id.btnSalvar).setOnClickListener(v -> {
            if (salvarDados(true)) Toast.makeText(this, "Perfil salvo", Toast.LENGTH_SHORT).show();
        });
        switchCheckin.setOnCheckedChangeListener((b, ligado) -> {
            if (atualizandoTela) return;
            salvarDados(false);
            if (Estado.monitorando) MonitorService.comando(this, MonitorService.ACAO_CHECKIN);
            else if (ligado) {
                Toast.makeText(this, "O check-in começa quando a proteção estiver ativa", Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.btnPermissoesBasicas).setOnClickListener(v -> solicitarPermissoes());
        findViewById(R.id.btnSobrepor).setOnClickListener(v -> abrirConfiguracao(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btnTelaCheia).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                abrirConfiguracao(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Nesta versão do Android já é permitido", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btnBateria).setOnClickListener(v -> pedirSemEconomiaDeBateria());

        TextView txtSobre = findViewById(R.id.txtSobre);
        txtSobre.setText("Cai Cai Balão 1.0 · " + Build.MANUFACTURER + " " + Build.MODEL);
    }

     private void executarNoServico(String acao) {
        if (!temLocalizacao()) {
            acaoPendente = acao;
            Toast.makeText(this, "Permita a localização para o alerta levar sua posição", Toast.LENGTH_LONG).show();
            solicitarPermissoes();
            return;
        }
        MonitorService.comando(this, acao);
    }


    @Override
    public void onEstadoMudou() {
        atualizarInicio();
        atualizarSensores();
        atualizarHistorico();

        if (Estado.lux < 0) aplicarModoNoturno(false);
        else if (!modoNoturno && Estado.lux < 10) aplicarModoNoturno(true);
        else if (modoNoturno && Estado.lux > 30) aplicarModoNoturno(false);
    }

    private void atualizarInicio() {
        String titulo, detalhe;
        int cor;
        if (Estado.contagemAtiva) {
            titulo = Estado.contagemTitulo;
            detalhe = "Pedido de ajuda em " + Estado.segundosRestantes + " segundos.";
            cor = LARANJA;
        } else if ("ALERTA ENVIADO".equals(Estado.status)) {
            titulo = "Pedido de ajuda enviado";
            detalhe = "Sua localização foi enviada para a central.";
            cor = VERMELHO;
        } else if (Estado.monitorando) {
            titulo = "Você está protegido";
            detalhe = "Monitorando quedas, mesmo com o app fechado.";
            cor = VERDE;
        } else if ("Monitoramento desligado".equals(Estado.status)) {
            titulo = "Proteção desligada";
            detalhe = "Ative para detectar quedas mesmo com o app fechado.";
            cor = NAVY_2;
        } else {
            titulo = "Atenção";
            detalhe = Estado.status;   // mensagens de erro do serviço
            cor = NAVY_2;
        }
        txtStatus.setText(titulo);
        txtStatusDetalhe.setText(detalhe);
        cardHero.setCardBackgroundColor(cor);
        btnProtecao.setText(Estado.monitorando ? "Desativar proteção" : "Ativar proteção");
        btnProtecao.setTextColor(cor);

         String clima = Estado.clima;
        int separador = clima.indexOf(" · ");
        if (clima.contains("°C") && separador > 0) {
            txtClimaResumo.setText(clima.substring(0, separador));
            txtClima.setText(clima.substring(separador + 3));
        } else {
            txtClimaResumo.setText("—");
            txtClima.setText(clima);
        }
        if (Estado.climaRisco) txtClimaResumo.setTextColor(LARANJA);
        else txtClimaResumo.setTextColor(modoNoturno ? Color.parseColor("#F1F5F9") : NAVY);

        boolean semEnvio = Estado.envioApi.startsWith("Nenhum");
        txtApiResumo.setText(semEnvio ? "—" : (Estado.envioOk ? "Online" : "Falhou"));
        txtApiResumo.setTextColor(semEnvio ? CINZA : (Estado.envioOk ? VERDE : VERMELHO));
        txtApi.setText(semEnvio ? "Nenhum envio ainda" : Estado.envioApi);
    }

    private void atualizarSensores() {
        if (!Estado.monitorando) {
            txtSensoresAviso.setText("Ative a proteção na tela Início para ver as leituras.");
            for (TextView t : new TextView[]{valAceleracao, valRotacao, valPosicao, valLuz, valPressao, valGps}) {
                t.setText("—");
            }
            txtLocalizacao.setText("");
            return;
        }
        txtSensoresAviso.setText("Leituras ao vivo, atualizadas 5 vezes por segundo.");
        valAceleracao.setText(String.format(Locale.getDefault(), "%.2f G", Estado.aceleracaoG));
        valRotacao.setText(String.format(Locale.getDefault(), "%.1f rad/s", Estado.rotacao));
        valPosicao.setText(Estado.noBolso ? "No bolso" : "Na mão");
        valLuz.setText(Estado.lux < 0 ? "—" : String.format(Locale.getDefault(), "%.0f lux", Estado.lux));
        valPressao.setText(Estado.pressaoHpa > 0
                ? String.format(Locale.getDefault(), "%.0f hPa", Estado.pressaoHpa) : "Sem sensor");
        valGps.setText(Estado.velocidadeKmh < 0 ? "—"
                : String.format(Locale.getDefault(), "%.0f km/h", Estado.velocidadeKmh));
        txtLocalizacao.setText("Localização: " + Estado.localizacao);
    }

     private void atualizarHistorico() {
        String texto = Estado.textoHistorico(this);
        if (texto.equals(ultimoHistorico)) return;
        ultimoHistorico = texto;

        listaHistorico.removeAllViews();
        boolean vazio = texto.equals("Nenhum evento");
        txtHistoricoVazio.setVisibility(vazio ? View.VISIBLE : View.GONE);
        if (vazio) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String linha : texto.split("\n")) {
            int sep = linha.indexOf(" — ");
            String hora = sep > 0 ? linha.substring(0, sep) : "";
            String evento = sep > 0 ? linha.substring(sep + 3) : linha;

            View item = inflater.inflate(R.layout.item_historico, listaHistorico, false);
            ((TextView) item.findViewById(R.id.txtEvento)).setText(evento);
            ((TextView) item.findViewById(R.id.txtHora)).setText(hora);
            item.findViewById(R.id.ponto).setBackgroundTintList(ColorStateList.valueOf(corDoEvento(evento)));
            listaHistorico.addView(item);
        }
        if (modoNoturno) pintar(listaHistorico);
    }

    private int corDoEvento(String evento) {
        if (evento.startsWith("ALERTA") || evento.startsWith("Falha")) return VERMELHO;
        if (evento.contains("cancelad") || evento.contains("Check-in")) return LARANJA;
        if (evento.contains("iniciado")) return VERDE;
        if (evento.contains("encerrado")) return CINZA;
        return AZUL;
    }


    private void solicitarPermissoes() {
        List<String> lista = new ArrayList<>();
        lista.add(Manifest.permission.ACCESS_FINE_LOCATION);
        lista.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        lista.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lista.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        pedirPermissoes.launch(lista.toArray(new String[0]));
    }

    private boolean temPermissao(String permissao) {
        return ContextCompat.checkSelfPermission(this, permissao) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean temLocalizacao() {
        return temPermissao(Manifest.permission.ACCESS_FINE_LOCATION)
                || temPermissao(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

     private void atualizarPermissoes() {
        boolean notificacoes = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || temPermissao(Manifest.permission.POST_NOTIFICATIONS);
        boolean telaCheia = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            telaCheia = getSystemService(NotificationManager.class).canUseFullScreenIntent();
        }
        boolean semEconomia = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());

        listaPermissoes.removeAllViews();
        adicionarLinhaPermissao("Localização", temLocalizacao());
        adicionarLinhaPermissao("Notificações", notificacoes);
        adicionarLinhaPermissao("SMS para o contato", temPermissao(Manifest.permission.SEND_SMS));
        adicionarLinhaPermissao("Abrir sobre outros apps", Settings.canDrawOverlays(this));
        adicionarLinhaPermissao("Alerta em tela cheia", telaCheia);
        adicionarLinhaPermissao("Sem economia de bateria", semEconomia);
        if (modoNoturno) pintar(listaPermissoes);
    }

    private void adicionarLinhaPermissao(String nome, boolean ok) {
        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        linha.setPadding(0, pad, 0, pad);

        TextView rotulo = new TextView(this);
        rotulo.setText(nome);
        rotulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        rotulo.setTextColor(NAVY);
        linha.addView(rotulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView situacao = new TextView(this);
        situacao.setText(ok ? "✓ Liberado" : "Falta liberar");
        situacao.setTag("fixo");
        situacao.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        situacao.setTextColor(ok ? VERDE : LARANJA);
        situacao.setTypeface(situacao.getTypeface(), android.graphics.Typeface.BOLD);
        linha.addView(situacao);

        listaPermissoes.addView(linha);
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }

    private void abrirConfiguracao(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @SuppressLint("BatteryLife")
    private void pedirSemEconomiaDeBateria() {
        abrirConfiguracao(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }


    @SuppressLint("MissingPermission")
    private void verificarClima() {
        if (!temLocalizacao()) {
            solicitarPermissoes();
            return;
        }
        String provedor = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER : null;
        if (provedor == null) {
            Toast.makeText(this, "Ative a localização do celular", Toast.LENGTH_SHORT).show();
            return;
        }
        Estado.clima = "Obtendo localização…";
        Estado.climaRisco = false;
        Estado.notificar();

        String provedorFinal = provedor;
        locationManager.getCurrentLocation(provedor, null, getMainExecutor(), local -> {
            Location l = local != null ? local : locationManager.getLastKnownLocation(provedorFinal);
            if (l == null) {
                Estado.clima = "Sem localização";
                Estado.notificar();
                return;
            }
            Estado.clima = "Consultando…";
            Estado.notificar();
            ApiClient.buscarClima(l.getLatitude(), l.getLongitude(), (clima, erro) -> {
                Estado.clima = clima != null ? clima.texto() : erro;
                Estado.climaRisco = clima != null && !clima.riscos().isEmpty();
                Estado.notificar();
            });
        });
    }


    private void carregarDados() {
        atualizandoTela = true;
        edtUsuario.setText(prefs.getString("usuario", ""));
        edtTelefone.setText(prefs.getString("telefone", ""));
        edtIntervalo.setText(String.valueOf(prefs.getInt("checkin_min", 15)));
        switchCheckin.setChecked(prefs.getBoolean("checkin_ativo", false));
        atualizandoTela = false;
        atualizarSaudacao();
    }

     private boolean salvarDados(boolean validar) {
        String nome = texto(edtUsuario);
        if (nome.length() < 2) {
            if (validar) {
                campoUsuario.setError("Informe seu nome");
                return false;
            }
            nome = prefs.getString("usuario", nome); // mantém o nome anterior
        }
        campoUsuario.setError(null);

        int minutos;
        try {
            minutos = Math.max(1, Integer.parseInt(texto(edtIntervalo)));
        } catch (NumberFormatException e) {
            minutos = 15;
        }
        prefs.edit()
                .putString("usuario", nome)
                .putString("telefone", texto(edtTelefone))
                .putInt("checkin_min", minutos)
                .putBoolean("checkin_ativo", switchCheckin.isChecked())
                .apply();
        atualizarSaudacao();
        return true;
    }

    private String texto(TextInputEditText campo) {
        return campo.getText() == null ? "" : campo.getText().toString().trim();
    }

    private void atualizarSaudacao() {
        String nome = prefs.getString("usuario", "").trim();
        String primeiro = nome.contains(" ") ? nome.substring(0, nome.indexOf(' ')) : nome;
        toolbar.setSubtitle(primeiro.isEmpty() ? null : "Olá, " + primeiro);
    }


    private void aplicarModoNoturno(boolean noturno) {
        if (modoNoturno == noturno) return;
        modoNoturno = noturno;
        raiz.setBackgroundColor(noturno ? Color.parseColor("#111827") : FUNDO);
        navegacao.setBackgroundColor(noturno ? Color.parseColor("#1F2937") : Color.WHITE);
        pintar((ViewGroup) raiz);
        atualizarInicio();
    }


    private void pintar(ViewGroup grupo) {
        int fundoCard = modoNoturno ? Color.parseColor("#1F2937") : Color.WHITE;
        int borda = modoNoturno ? Color.parseColor("#374151") : BORDA;
        int textoPrincipal = modoNoturno ? Color.parseColor("#F9FAFB") : NAVY;
        int textoSecundario = modoNoturno ? Color.parseColor("#D1D5DB") : CINZA;

        for (int i = 0; i < grupo.getChildCount(); i++) {
            View v = grupo.getChildAt(i);
            if ("fixo".equals(v.getTag())) continue;
            if (v instanceof MaterialCardView) {
                ((MaterialCardView) v).setCardBackgroundColor(fundoCard);
                ((MaterialCardView) v).setStrokeColor(borda);
            }
            if (v instanceof ViewGroup) {
                pintar((ViewGroup) v);
            } else if (v instanceof TextView && !(v instanceof MaterialButton)) {
                ((TextView) v).setTextColor("sec".equals(v.getTag()) ? textoSecundario : textoPrincipal);
            }
        }
    }
}
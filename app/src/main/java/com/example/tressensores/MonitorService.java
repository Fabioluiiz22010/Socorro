package com.example.tressensores;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.SmsManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class MonitorService extends Service implements FallDetector.Listener {

    //comandos das telas e as notificações q podem enviar
    public static final String ACAO_INICIAR = "com.example.tressensores.INICIAR";
    public static final String ACAO_PARAR = "com.example.tressensores.PARAR";
    public static final String ACAO_PANICO = "com.example.tressensores.PANICO";
    public static final String ACAO_SIMULAR = "com.example.tressensores.SIMULAR";
    public static final String ACAO_CANCELAR = "com.example.tressensores.CANCELAR";
    public static final String ACAO_ENVIAR_AGORA = "com.example.tressensores.ENVIAR_AGORA";
    public static final String ACAO_CHECKIN = "com.example.tressensores.CHECKIN";

    // tipo_registro da API
    private static final int TIPO_NORMAL = 1;
    private static final int TIPO_QUEDA = 2;

    private static final int CONTAGEM_QUEDA_S = 30;
    private static final int CONTAGEM_PANICO_S = 5;
    private static final int CONTAGEM_CHECKIN_S = 30;
    private static final long INTERVALO_TELEMETRIA_MS = 60_000;
    private static final long INTERVALO_CLIMA_MS = 10 * 60_000;
    private static final int MAX_TENTATIVAS_ALERTA = 3;

    private static final String CANAL_MONITOR = "monitoramento";
    private static final String CANAL_ALERTA = "alerta_queda";
    private static final int NOTIF_MONITOR = 1;
    private static final int NOTIF_ALERTA = 2;

    private static final String VERDE = "#2E7D32", LARANJA = "#E65100", VERMELHO = "#C62828", CINZA = "#757575";
    private static final String STATUS_OK = "Monitorando — tudo certo";

    public static void comando(Context ctx, String acao) {
        Intent i = new Intent(ctx, MonitorService.class).setAction(acao);
        ContextCompat.startForegroundService(ctx, i);
    }

    private SharedPreferences prefs;
    private FallDetector detector;
    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private Location ultimaLocalizacao;
    private Double temperaturaClima;
    private float ultimaPressao = 0f;
    private long ultimoClimaMs = 0;

    private boolean emPrimeiroPlano = false;
    private PowerManager.WakeLock wakeLock;
    private ToneGenerator bipe;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CountDownTimer contagem;
    private float contagemNivelQueda = 0f;
    private Runnable contagemAoCancelar;


    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("socorro", MODE_PRIVATE);
        detector = new FallDetector(this, this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        try {
            bipe = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            bipe = null;
        }
        criarCanais();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!garantirPrimeiroPlano()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String acao = intent != null ? intent.getAction() : null;
        if (acao == null) {

            if (prefs.getBoolean("monitorar", false)) iniciarMonitoramento();
            else pararSeOcioso();
            return START_STICKY;
        }

        switch (acao) {
            case ACAO_INICIAR:
                iniciarMonitoramento();
                break;
            case ACAO_PARAR:
                pararMonitoramento();
                pararSeOcioso();
                break;
            case ACAO_PANICO:
                iniciarContagem("Botão de pânico", "Pedido de ajuda manual (botão de pânico)",
                        CONTAGEM_PANICO_S, 0f, "Cancelar",
                        () -> Estado.adicionarHistorico(this, "Pânico cancelado pelo usuário"));
                break;
            case ACAO_SIMULAR:
                onQuedaDetectada(4.5f, true, 5.2f, 0f);
                break;
            case ACAO_CANCELAR:
                cancelarContagem();
                break;
            case ACAO_ENVIAR_AGORA:
                if (Estado.contagemAtiva) {
                    contagem.cancel();
                    dispararAlerta();
                }
                break;
            case ACAO_CHECKIN:
                agendarCheckin();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        detector.parar();
        locationManager.removeUpdates(ouvinteLocalizacao);
        handler.removeCallbacksAndMessages(null);
        if (contagem != null) contagem.cancel();
        notificationManager.cancel(NOTIF_ALERTA);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (bipe != null) bipe.release();

        Estado.monitorando = false;
        Estado.contagemAtiva = false;
        Estado.sensores = "Sensores parados";
        Estado.lux = -1f;
        Estado.definirStatus("Monitoramento desligado", CINZA);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @SuppressLint("WakelockTimeout")
    private boolean garantirPrimeiroPlano() {
        if (emPrimeiroPlano) return true;
        if (!temPermissao(Manifest.permission.ACCESS_FINE_LOCATION)
                && !temPermissao(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Estado.definirStatus("Permita a localização para monitorar", VERMELHO);
            return false;
        }
        try {
            startForeground(NOTIF_MONITOR, notificacaoMonitor("Pronto"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Exception e) {
            Estado.definirStatus("Não foi possível iniciar em segundo plano", VERMELHO);
            return false;
        }
        emPrimeiroPlano = true;

         PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TresSensores:monitor");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        iniciarLocalizacao();
        return true;
    }

    private void pararSeOcioso() {
        if (!Estado.monitorando && !Estado.contagemAtiva) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private boolean temPermissao(String permissao) {
        return ContextCompat.checkSelfPermission(this, permissao) == PackageManager.PERMISSION_GRANTED;
    }


    private void iniciarMonitoramento() {
        if (Estado.monitorando) return;
        if (!detector.temAcelerometro()) {
            Estado.definirStatus("Este aparelho não tem acelerômetro", VERMELHO);
            pararSeOcioso();
            return;
        }
        List<String> ausentes = detector.iniciar();
        Estado.monitorando = true;
        prefs.edit().putBoolean("monitorar", true).apply();
        Estado.definirStatus(STATUS_OK, VERDE);
        if (!ausentes.isEmpty()) {
            Estado.adicionarHistorico(this, "Sensores ausentes: " + String.join(", ", ausentes));
        }
        Estado.adicionarHistorico(this, "Monitoramento iniciado");
        atualizarNotificacaoMonitor("Monitorando quedas. Pode fechar o app.");
        handler.post(rotinaTelemetria);
        agendarCheckin();
    }

    private void pararMonitoramento() {
        prefs.edit().putBoolean("monitorar", false).apply();
        if (!Estado.monitorando) return;
        detector.parar();
        handler.removeCallbacks(rotinaTelemetria);
        handler.removeCallbacks(rotinaCheckin);
        Estado.monitorando = false;
        Estado.sensores = "Sensores parados";
        Estado.lux = -1f;
        Estado.definirStatus("Monitoramento desligado", CINZA);
        Estado.adicionarHistorico(this, "Monitoramento encerrado");
    }


    private final Runnable rotinaTelemetria = new Runnable() {
        @Override
        public void run() {
            if (!Estado.monitorando) return;
            ApiClient.enviarRegistro(montarJson(TIPO_NORMAL, 0f), (ok, codigo, corpo) ->
                    registrarEnvio("Telemetria", ok, codigo, corpo));
            handler.postDelayed(this, INTERVALO_TELEMETRIA_MS);
        }
    };

    @Override
    public void onLeituras(float aceleracaoG, float rotacao, boolean noBolso, float lux, float pressaoHpa) {
        ultimaPressao = pressaoHpa;
        Estado.lux = lux;
        Estado.aceleracaoG = aceleracaoG;
        Estado.rotacao = rotacao;
        Estado.noBolso = noBolso;
        Estado.pressaoHpa = pressaoHpa;
        if (ultimaLocalizacao != null) {
            Estado.velocidadeKmh = ultimaLocalizacao.hasSpeed() ? ultimaLocalizacao.getSpeed() * 3.6 : 0;
            Estado.localizacao = String.format(Locale.US, "%.5f, %.5f (±%.0f m)",
                    ultimaLocalizacao.getLatitude(), ultimaLocalizacao.getLongitude(), ultimaLocalizacao.getAccuracy());
        }
        Estado.sensores = String.format(Locale.getDefault(),
                "Aceleração : %.2f G\nRotação    : %.2f rad/s\nPosição    : %s\nLuz        : %s\nPressão    : %s\nGPS        : %s",
                aceleracaoG, rotacao,
                noBolso ? "no bolso/mochila" : "na mão",
                lux < 0 ? "—" : String.format(Locale.getDefault(), "%.0f lux", lux),
                pressaoHpa > 0 ? String.format(Locale.getDefault(), "%.1f hPa", pressaoHpa) : "—",
                ultimaLocalizacao == null ? "aguardando…" : String.format(Locale.US, "%.5f, %.5f",
                        ultimaLocalizacao.getLatitude(), ultimaLocalizacao.getLongitude()));
        Estado.notificar();
    }

    @Override
    public void onQuedaDetectada(float picoG, boolean houveQuedaLivre, float rotacaoMax, float quedaAltitudeM) {
        if (Estado.contagemAtiva) return;
        StringBuilder motivo = new StringBuilder(String.format(Locale.getDefault(),
                "Queda detectada: impacto de %.1f G", picoG));
        if (houveQuedaLivre) motivo.append(", queda livre");
        if (rotacaoMax > 0) motivo.append(String.format(Locale.getDefault(), ", rotação %.1f rad/s", rotacaoMax));
        if (quedaAltitudeM >= 1f) motivo.append(String.format(Locale.getDefault(), ", desceu ~%.1f m", quedaAltitudeM));

        String descricao = motivo.toString();
        iniciarContagem("Você caiu?", descricao, CONTAGEM_QUEDA_S, picoG, "Estou bem — cancelar",
                () -> Estado.adicionarHistorico(this, "Alarme falso cancelado — " + descricao));
    }


    private void iniciarContagem(String titulo, String motivo, int segundos, float nivelQueda,
                                 String textoCancelar, Runnable aoCancelar) {
        if (Estado.contagemAtiva) return;
        Estado.contagemAtiva = true;
        Estado.contagemTitulo = titulo;
        Estado.contagemMotivo = motivo;
        Estado.contagemTextoCancelar = textoCancelar;
        Estado.segundosRestantes = segundos;
        contagemNivelQueda = nivelQueda;
        contagemAoCancelar = aoCancelar;
        Estado.definirStatus(titulo, LARANJA);

        mostrarNotificacaoAlerta();
        abrirTelaAlerta();

        contagem = new CountDownTimer(segundos * 1000L, 1000) {
            @Override
            public void onTick(long msRestantes) {
                Estado.segundosRestantes = (int) Math.ceil(msRestantes / 1000.0);
                Estado.notificar();
                mostrarNotificacaoAlerta();
                vibrar(300);
                if (bipe != null) bipe.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
            }

            @Override
            public void onFinish() {
                Estado.segundosRestantes = 0;
                dispararAlerta();
            }
        }.start();
    }


    private void abrirTelaAlerta() {
        if (Estado.telaPrincipalVisivel || Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(this, AlertaActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (Exception ignored) {
                //a notificação em tela cheia
            }
        }
    }

    private void cancelarContagem() {
        if (!Estado.contagemAtiva) return;
        contagem.cancel();
        Estado.contagemAtiva = false;
        notificationManager.cancel(NOTIF_ALERTA);
        if (Estado.monitorando) Estado.definirStatus(STATUS_OK, VERDE);
        else Estado.definirStatus("Monitoramento desligado", CINZA);
        if (contagemAoCancelar != null) contagemAoCancelar.run();
        pararSeOcioso();
    }

    private void dispararAlerta() {
        String motivo = Estado.contagemMotivo;
        Estado.contagemAtiva = false;
        Estado.definirStatus("ALERTA ENVIADO", VERMELHO);
        vibrar(1500);

        enviarAlertaApi(montarJson(TIPO_QUEDA, contagemNivelQueda), 1);
        boolean smsEnviado = enviarSms(motivo);
        Estado.adicionarHistorico(this, "ALERTA: " + motivo + (smsEnviado ? " (SMS enviado)" : ""));
        mostrarNotificacaoEnviado(smsEnviado);

        if (Estado.monitorando) {
            agendarCheckin();
        } else {
            //espera as novas tentativas de envio antes de desligar
            handler.postDelayed(this::pararSeOcioso, 40_000);
        }
    }



    private void enviarAlertaApi(JSONObject json, int tentativa) {
        ApiClient.enviarRegistro(json, (ok, codigo, corpo) -> {
            registrarEnvio("Alerta (tentativa " + tentativa + ")", ok, codigo, corpo);
            if (!ok && tentativa < MAX_TENTATIVAS_ALERTA) {
                handler.postDelayed(() -> enviarAlertaApi(json, tentativa + 1), 10_000);
            } else if (!ok) {
                Estado.adicionarHistorico(this, "Falha ao enviar alerta para a API: " + corpo);
            }
        });
    }

    private void registrarEnvio(String tipo, boolean ok, int codigo, String corpo) {
        String hora = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String motivo;
        if (ok) motivo = "enviado com sucesso";
        else if (codigo == -1) motivo = corpo;
        else motivo = "servidor respondeu com erro " + codigo;
        Estado.envioApi = tipo + " às " + hora + ": " + motivo;
        Estado.envioOk = ok;
        Estado.notificar();
    }
    private boolean enviarSms(String motivo) {
        String telefone = prefs.getString("telefone", "").trim();
        if (telefone.isEmpty() || !temPermissao(Manifest.permission.SEND_SMS)) return false;

        String local = ultimaLocalizacao != null
                ? String.format(Locale.US, " Local: https://maps.google.com/?q=%.6f,%.6f",
                ultimaLocalizacao.getLatitude(), ultimaLocalizacao.getLongitude())
                : " Localização indisponível.";
        String mensagem = "SOCORRO! " + nomeUsuario() + " pode ter sofrido um acidente. " + motivo + "." + local;

        try {
            SmsManager sms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            sms.sendMultipartTextMessage(telefone, null, sms.divideMessage(mensagem), null, null);
            return true;
        } catch (Exception e) {
            Estado.adicionarHistorico(this, "Falha no SMS: " + e.getMessage());
            return false;
        }
    }


    private JSONObject montarJson(int tipoRegistro, float nivelQueda) {
        JSONObject j = new JSONObject();
        try {
            j.put("usuario", nomeUsuario());
            j.put("tipo_registro", tipoRegistro);
            j.put("device_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            j.put("modelo", Build.MANUFACTURER + " " + Build.MODEL);
            j.put("bateria", nivelBateria());
            if (ultimaLocalizacao != null) {
                j.put("latitude", ultimaLocalizacao.getLatitude());
                j.put("longitude", ultimaLocalizacao.getLongitude());
                double kmh = ultimaLocalizacao.hasSpeed() ? ultimaLocalizacao.getSpeed() * 3.6 : 0;
                j.put("velocidade", arredondar(kmh, 1));
            }
            if (temperaturaClima != null) j.put("temperatura", arredondar(temperaturaClima, 1));
            if (ultimaPressao > 0) j.put("pressao", arredondar(ultimaPressao, 1));
            j.put("nivel_queda", arredondar(nivelQueda, 2));
        } catch (Exception ignored) {
        }
        return j;
    }

    private double arredondar(double valor, int casas) {
        double f = Math.pow(10, casas);
        return Math.round(valor * f) / f;
    }

    private float nivelBateria() {
        BatteryManager bm = getSystemService(BatteryManager.class);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0f;
    }

    private String nomeUsuario() {
        String nome = prefs.getString("usuario", "").trim();
        return nome.isEmpty() ? "Usuário Anônimo" : nome;
    }


    private void agendarCheckin() {
        handler.removeCallbacks(rotinaCheckin);
        if (!Estado.monitorando || !prefs.getBoolean("checkin_ativo", false)) return;
        int minutos = Math.max(1, prefs.getInt("checkin_min", 15));
        handler.postDelayed(rotinaCheckin, minutos * 60_000L);
    }

    private final Runnable rotinaCheckin = new Runnable() {
        @Override
        public void run() {
            if (Estado.contagemAtiva) {
                agendarCheckin();
                return;
            }
            iniciarContagem("Check-in de segurança",
                    "Está tudo bem? Sem resposta ao check-in de segurança",
                    CONTAGEM_CHECKIN_S, 0f, "Estou bem", () -> {
                        Estado.adicionarHistorico(MonitorService.this, "Check-in respondido: tudo bem");
                        agendarCheckin();
                    });
        }
    };


    private final LocationListener ouvinteLocalizacao = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            ultimaLocalizacao = location;
            if (System.currentTimeMillis() - ultimoClimaMs > INTERVALO_CLIMA_MS) buscarClima();
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }
    };

    @SuppressLint("MissingPermission")
    private void iniciarLocalizacao() {
        for (String provedor : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!locationManager.isProviderEnabled(provedor)) continue;
                locationManager.requestLocationUpdates(provedor, 5000, 0, ouvinteLocalizacao, Looper.getMainLooper());
                Location ultima = locationManager.getLastKnownLocation(provedor);
                if (ultima != null && (ultimaLocalizacao == null || ultima.getTime() > ultimaLocalizacao.getTime())) {
                    ultimaLocalizacao = ultima;
                }
            } catch (Exception ignored) {

            }
        }
        if (ultimaLocalizacao != null) buscarClima();
    }

    private void buscarClima() {
        if (ultimaLocalizacao == null) return;
        ultimoClimaMs = System.currentTimeMillis();
        ApiClient.buscarClima(ultimaLocalizacao.getLatitude(), ultimaLocalizacao.getLongitude(), (clima, erro) -> {
            if (clima != null) {
                temperaturaClima = clima.temperatura;
                Estado.clima = clima.texto();
                Estado.climaRisco = !clima.riscos().isEmpty();
            } else {
                Estado.clima = erro;
                Estado.climaRisco = false;
            }
            Estado.notificar();
        });
    }


    private void criarCanais() {
        NotificationChannel monitor = new NotificationChannel(CANAL_MONITOR,
                "Monitoramento de quedas", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Aviso fixo enquanto o app monitora em segundo plano");

        NotificationChannel alerta = new NotificationChannel(CANAL_ALERTA,
                "Alerta de queda", NotificationManager.IMPORTANCE_HIGH);
        alerta.setDescription("Contagem regressiva antes de pedir ajuda");
        alerta.setSound(null, null);          // o bipe é tocado pelo próprio serviço
        alerta.enableVibration(false);        // a vibração também
        alerta.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alerta.setBypassDnd(true);

        notificationManager.createNotificationChannel(monitor);
        notificationManager.createNotificationChannel(alerta);
    }

    private PendingIntent intentServico(int codigo, String acao) {
        Intent i = new Intent(this, MonitorService.class).setAction(acao);
        return PendingIntent.getService(this, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notificacaoMonitor(String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CANAL_MONITOR)
                .setSmallIcon(R.drawable.ic_escudo)
                .setContentTitle("Socorro está ativo")
                .setContentText(texto)
                .setOngoing(true)
                .setContentIntent(abrir)
                .addAction(0, "Parar", intentServico(1, ACAO_PARAR))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void atualizarNotificacaoMonitor(String texto) {
        notificar(NOTIF_MONITOR, notificacaoMonitor(texto));
    }

    private void mostrarNotificacaoAlerta() {
        Intent tela = new Intent(this, AlertaActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piTela = PendingIntent.getActivity(this, 2, tela,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String texto = "Pedido de ajuda em " + Estado.segundosRestantes + " s. Toque para cancelar.";
        Notification n = new NotificationCompat.Builder(this, CANAL_ALERTA)
                .setSmallIcon(R.drawable.ic_escudo)
                .setContentTitle(Estado.contagemTitulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(Estado.contagemMotivo + "\n\n" + texto))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piTela)
                .setFullScreenIntent(piTela, true)   // abre a tela de alerta sozinho
                .addAction(0, Estado.contagemTextoCancelar, intentServico(3, ACAO_CANCELAR))
                .addAction(0, "Enviar agora", intentServico(4, ACAO_ENVIAR_AGORA))
                .build();
        notificar(NOTIF_ALERTA, n);
    }

    private void mostrarNotificacaoEnviado(boolean smsEnviado) {
        PendingIntent abrir = PendingIntent.getActivity(this, 5, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CANAL_ALERTA)
                .setSmallIcon(R.drawable.ic_escudo)
                .setContentTitle("Pedido de ajuda enviado")
                .setContentText(smsEnviado ? "Localização enviada para a central e por SMS ao seu contato."
                        : "Localização enviada para a central.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .build();
        notificar(NOTIF_ALERTA, n);
    }

    private void notificar(int id, Notification n) {
        try {
            notificationManager.notify(id, n);
        } catch (SecurityException ignored) {
            //sem permissão de notificação
        }
    }

    private void vibrar(long ms) {
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}

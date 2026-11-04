package ru.arenahelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import kotlin.UByte;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;


public final class CaptureService extends Service {
    private volatile long autoCandidateAt;
    private volatile boolean calibrating;
    private View calibration;
    private DraftCatalog catalog;
    private LinearLayout controls;
    private volatile int deckCount;
    private TextView deckLabel;
    private VirtualDisplay display;
    private volatile int generation;
    private int height;
    private boolean heroMode;
    private long lastFrame;
    private long lastMotionFrame;
    private long lastTap;
    private String manuallyCountedOffer = null;
    private volatile boolean motionActive;
    private int motionFrames;
    private LinearLayout panel;
    private volatile int panelHeight;
    private volatile boolean paused;
    private SharedPreferences prefs;
    private MediaProjection projection;
    private ImageReader reader;
    private int stable;
    private TextView status;
    private volatile boolean stopped;
    private int width;
    private WindowManager wm;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("arena-art");
    private final List<TextView> cells = new ArrayList();
    private String previousKey = "";
    private volatile String currentClass = "mage";
    private final List<Integer> deckCosts = new ArrayList();
    private final List<Match> currentMatches = new ArrayList();
    private final boolean[] currentFirst = new boolean[3];
    private String activeHeroKey = "";
    private final List<DraftCatalog.ClassStat> currentHeroes = new ArrayList();
    private ArtReader art;
    private final LastPick lastPick = new LastPick();
    private volatile long manualLastPickAt = -10000L;
    private volatile int autoCandidate = -1;
    private volatile String trackedKey = "";
    private volatile List<Match> trackedMatches = new ArrayList();
    private int[][] motionSamples = new int[3][];
    private final double[] motionBaseline = new double[3];

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int i) {
        return Math.round(i * getResources().getDisplayMetrics().density);
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        this.wm = (WindowManager) getSystemService(WindowManager.class);
        this.prefs = getSharedPreferences("arena", 0);
        this.currentClass = this.prefs.getString("class", "mage");
        loadDeck();
        this.thread.start();
        this.worker = new Handler(this.thread.getLooper());
        ((NotificationManager) getSystemService(NotificationManager.class)).createNotificationChannel(new NotificationChannel("capture", "Захват Арены", NotificationManager.IMPORTANCE_LOW));
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try {
            Intent data=intent==null?null:(Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("data",Intent.class):intent.getParcelableExtra("data"));
            if(data==null)throw new IllegalStateException("Нет разрешения на захват");
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
            startForeground(1,new Notification.Builder(this,"capture").setContentTitle("Arena Helper RU").setContentText("Локальное распознавание экрана включено").setSmallIcon(android.R.drawable.ic_menu_view).addAction(new Notification.Action.Builder((Icon)null,"Остановить",stop).build()).setOngoing(true).build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("resultCode",0),data);
            if(projection==null)throw new IllegalStateException("MediaProjection не получен");
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){main.post(() -> stopSelf());}
                @Override public void onCapturedContentResize(int w,int h){worker.post(() -> {if(art!=null)resize(w,h);});}
            },main);
            showPanel();worker.post(() -> initialize());
        }catch(Exception e){fail(e);}
        return START_NOT_STICKY;
    }

    public void initialize() {
        try {
            this.catalog = new DraftCatalog(this);
            this.art = new ArtReader(this, catalog);
            File oldModel = new File(getFilesDir(), "ocr/tessdata/rus.traineddata");
            if (oldModel.exists()) oldModel.delete();
            Rect bounds = this.wm.getMaximumWindowMetrics().getBounds();
            resize(Math.max(bounds.width(), bounds.height()), Math.min(bounds.width(), bounds.height()));
            this.main.post(new Runnable() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda14
                @Override // java.lang.Runnable
                public final void run() {
                    CaptureService.this.m1493lambda$1$ruarenahelperCaptureService();
                }
            });
        } catch (Throwable th5) {
            fail(th5);
        }
    }

    
     void m1493lambda$1$ruarenahelperCaptureService() {
        this.status.setText("Готово • автоматический учёт выбранных карт");
    }

    private void resize(final int i, final int i2) {
        if (this.stopped || i < 2 || i2 < 2) {
            return;
        }
        if (i == this.width && i2 == this.height && this.reader != null) {
            return;
        }
        this.width = i;
        this.height = i2;
        this.previousKey = "";
        this.stable = 0;
        resetMotion();
        if (this.display != null) {
            this.display.setSurface(null);
        }
        if (this.reader != null) {
            this.reader.close();
        }
        ImageReader imageReaderNewInstance = ImageReader.newInstance(i, i2, android.graphics.PixelFormat.RGBA_8888, 2);
        this.reader = imageReaderNewInstance;
        imageReaderNewInstance.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda11
            @Override // android.media.ImageReader.OnImageAvailableListener
            public final void onImageAvailable(ImageReader imageReader) {
                CaptureService.this.m1501lambda$2$ruarenahelperCaptureService(i, i2, imageReader);
            }
        }, this.worker);
        if (this.display == null) {
            this.display = this.projection.createVirtualDisplay("ArenaCapture", i, i2, getResources().getDisplayMetrics().densityDpi, 16, imageReaderNewInstance.getSurface(), null, this.worker);
        } else {
            this.display.resize(i, i2, getResources().getDisplayMetrics().densityDpi);
            this.display.setSurface(imageReaderNewInstance.getSurface());
        }
    }

    
    
    
    
    
    
    public void m1501lambda$2$ruarenahelperCaptureService(int ignoredW, int ignoredH, ImageReader source) {
        Image image=null;Bitmap bitmap=null;
        try {
            image=source.acquireLatestImage();
            if(image==null || stopped || paused || calibrating || art==null)return;
            if(getResources().getConfiguration().orientation!=2){main.post(() -> m1502lambda$3$ruarenahelperCaptureService());return;}
            int w=image.getWidth(),h=image.getHeight();if(w<h)return;
            long now=SystemClock.elapsedRealtime();boolean motion=motionActive && now-lastMotionFrame>=90;boolean scan=now-lastFrame>=650;
            if(!motion && !scan)return;
            if(image.getPlanes().length==0)return;Image.Plane p=image.getPlanes()[0];
            if(motion){lastMotionFrame=now;updateMotion(p,w,h,now);}
            if(!scan)return;lastFrame=now;
            int[] pixels=FramePixels.INSTANCE.decode(p.getBuffer(),w,h,p.getRowStride(),p.getPixelStride());if(pixels==null)return;
            bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);recognize(bitmap,w,h);
        } catch(Exception e){Log.e("ArenaHelper","Frame recognition failed",e);main.post(() -> m1503lambda$4$ruarenahelperCaptureService(e));}
        finally {if(bitmap!=null)bitmap.recycle();if(image!=null)image.close();}
    }

    void m1502lambda$3$ruarenahelperCaptureService() {
        this.status.setText("Поверните устройство горизонтально");
    }

    
     void m1503lambda$4$ruarenahelperCaptureService(Exception exc) {
        this.status.setText("Ошибка кадра: " + exc.getClass().getSimpleName());
    }

    // Hearthstone's wooden side rails persist after the offer disappears. Ignore blank
    // frames and switches to unrelated apps when deciding whether to stop projection.
    private boolean hasGameFrame(Bitmap frame) {
        int width = frame.getWidth(), height = frame.getHeight();
        float board = Math.min(width, height * 16f / 9f);
        int brown = 0;
        for (float edge : new float[]{0.015f, 0.035f, 0.965f, 0.985f})
            for (float y : new float[]{0.2f, 0.4f, 0.67f, 0.8f}) {
                int color = frame.getPixel((int)((width-board)/2+edge*board), (int)(height*y));
                int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
                if (red > green && green > blue && red - blue > 14 && red > 28) brown++;
            }
        return brown >= 4;
    }

    private void finishDraft() {
        setDeckCount(30);
        generation++;
        Toast.makeText(this, "Колода собрана • 30/30", Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private void recognize(Bitmap bitmap, int i, int i2) {
        StringBuilder sb;
        String string;
        String strInferClass;
        Match matchRecognizeRegion;
        final int i3 = this.generation;
        List<RectF> listLoad = Regions.INSTANCE.defaults(i, i2);
        List<RectF> listDefaults = Regions.INSTANCE.defaults(i, i2);
        ArrayList<Match> arrayList = new ArrayList();
        boolean z = false;
        for (int i4 = 0; i4 < 3; i4++) {
            Match matchRecognizeRegion2 = art.card(bitmap, listLoad.get(i4));
            if ((matchRecognizeRegion2 == null || matchRecognizeRegion2.getConfidence() < 0.9d) && !same(listLoad.get(i4), listDefaults.get(i4)) && (matchRecognizeRegion = art.card(bitmap, listDefaults.get(i4))) != null && (matchRecognizeRegion2 == null || matchRecognizeRegion.getConfidence() > matchRecognizeRegion2.getConfidence())) {
                matchRecognizeRegion2 = matchRecognizeRegion;
            }
            arrayList.add(matchRecognizeRegion2);
        }
        Iterator it = arrayList.iterator();
        int i5 = 0;
        while (it.hasNext()) {
            if (((Match) it.next()) != null) {
                i5++;
            }
        }
        long now = SystemClock.elapsedRealtime();
        boolean selection = (autoCandidate >= 0 && now - autoCandidateAt < 2000) || now - manualLastPickAt < 4000;
        if (lastPick.observe(deckCount, i5, selection, deckCount == 29 && hasGameFrame(bitmap), now)) {
            main.post(() -> { if (generation == i3 && !paused && !stopped && deckCount == 29) finishDraft(); });
            return;
        }
        List<DraftCatalog.ClassStat> listRecognize = i5 < 2 ? art.heroSlots(bitmap) : Collections.emptyList();
        ArrayList arrayList2 = new ArrayList();
        for (Match match : arrayList) {
            arrayList2.add(match == null ? "?" : match.getCard().getName());
        }
        final boolean z2 = !listRecognize.isEmpty();
        if (z2) {
            sb = new StringBuilder("hero:");
            Iterator<DraftCatalog.ClassStat> it2 = listRecognize.iterator();
            while (it2.hasNext()) {
                DraftCatalog.ClassStat hero = it2.next();
                sb.append(hero == null ? "?" : hero.id).append(';');
            }
            string = sb.toString();
        } else {
            sb = new StringBuilder("cards:");
            string = sb.append(arrayList2.toString()).toString();
        }
        if (!string.equals(this.previousKey)) {
            this.stable = 0;
        } else {
            this.stable++;
        }
        this.previousKey = string;
        if (this.stable < 1) {
            return;
        }
        if (!z2 && (strInferClass = this.catalog.inferClass(arrayList)) != null && !strInferClass.equals(this.currentClass)) {
            this.currentClass = strInferClass;
            this.prefs.edit().putString("class", strInferClass).apply();
        }
        final ArrayList arrayList3 = new ArrayList(arrayList);
        final ArrayList arrayList4 = new ArrayList(listRecognize);
        if (z2) {
            this.motionActive = false;
            this.autoCandidate = -1;
        } else if (i5 >= 2 && !string.equals(this.trackedKey)) {
            if (this.motionActive && isNextChoices(this.trackedMatches, arrayList3)) {
                commitAutomaticPick(i3);
            }
            this.trackedKey = string;
            this.trackedMatches = new ArrayList(arrayList3);
            if (i5 >= 2) {
                z = true;
            }
            this.motionActive = z;
            resetMotion();
        }
        final String str = string;
        this.main.post(new Runnable() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                CaptureService.this.m1504lambda$5$ruarenahelperCaptureService(i3, z2, arrayList4, str, arrayList3);
            }
        });
    }

    
     void m1504lambda$5$ruarenahelperCaptureService(int i, boolean z, List list, String str, List list2) {
        if (i != this.generation || this.stopped) {
            return;
        }
        if (z) {
            renderHeroes(list, str);
        } else {
            renderCards(list2);
        }
    }

    private boolean isNextChoices(List<Match> list, List<Match> list2) {
        if (list == null || list.size() != 3 || list2.size() != 3) {
            return false;
        }
        Iterator<Match> it = list2.iterator();
        int i = 0;
        while (it.hasNext()) {
            if (it.next() != null) {
                i++;
            }
        }
        if (i < 2) {
            return false;
        }
        int i2 = 0;
        for (Match match : list) {
            if (match != null) {
                for (Match match2 : list2) {
                    if (match2 != null && match.getCard().getName().equals(match2.getCard().getName())) {
                        i2++;
                        break;
                    }
                }
            }
        }
        if (i2 > 1) {
            return false;
        }
        return true;
    }

    private void commitAutomaticPick(final int generationAtFrame) {
        int slot = this.autoCandidate;
        long age = SystemClock.elapsedRealtime() - this.autoCandidateAt;
        this.autoCandidate = -1;
        Match picked = slot >= 0 && slot < trackedMatches.size() && age <= 8000 ? trackedMatches.get(slot) : null;
        final String name = picked == null ? null : picked.getCard().getName();
        main.post(() -> {
            if (generationAtFrame != generation || stopped || deckCount >= 30) return;
            DraftCatalog.Details details = name == null ? null : catalog.details(name);
            if (details != null) addPick(details, deckCount == 0);
            else setDeckCount(Math.min(30, deckCount + (deckCount == 0 ? 4 : 1)));
        });
    }

    private void resetMotion() {
        this.motionSamples = new int[3][];
        Arrays.fill(this.motionBaseline, 0.0d);
        this.motionFrames = 0;
    }

    private void updateMotion(Image.Plane plane, int i, int i2, long j) {
        if (this.motionActive) {
            if (this.autoCandidate >= 0) {
                if (j - this.autoCandidateAt <= 8000) {
                    return;
                }
                this.autoCandidate = -1;
                resetMotion();
            }
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride < 4 || rowStride < i * pixelStride) {
                return;
            }
            int[][] iArr = new int[3][];
            int i3 = 0;
            while (i3 < 3) {
                int i4 = i3;
                iArr[i4] = sampleCard(buffer, i, i2, rowStride, pixelStride, i3);
                if (iArr[i4] == null) {
                    return;
                } else {
                    i3 = i4 + 1;
                }
            }
            if (this.motionSamples[0] == null) {
                this.motionSamples = iArr;
                return;
            }
            double[] dArr = new double[3];
            double d = 0.0d;
            double d2 = 0.0d;
            double d3 = 0.0d;
            int i5 = -1;
            for (int i6 = 0; i6 < 3; i6++) {
                long jAbs = 0;
                int i7 = 0;
                while (i7 < iArr[i6].length) {
                    jAbs += (long) Math.abs(iArr[i6][i7] - this.motionSamples[i6][i7]);
                    i7++;
                    d = 0.0d;
                }
                dArr[i6] = jAbs / ((double) iArr[i6].length);
                if (dArr[i6] > d2) {
                    i5 = i6;
                    d3 = d2;
                    d2 = dArr[i6];
                } else if (dArr[i6] > d3) {
                    d3 = dArr[i6];
                }
            }
            this.motionFrames++;
            if (this.motionFrames > 6 && d2 > 14.0d && d2 > (this.motionBaseline[i5] * 1.8d) + 4.0d && d2 > (d3 * 1.3d) + 3.0d) {
                this.autoCandidate = i5;
                this.autoCandidateAt = j;
            }
            for (int i8 = 0; i8 < 3; i8++) {
                this.motionBaseline[i8] = this.motionBaseline[i8] == d ? dArr[i8] : (this.motionBaseline[i8] * 0.9d) + (dArr[i8] * 0.1d);
            }
            this.motionSamples = iArr;
        }
    }

    private int[] sampleCard(ByteBuffer byteBuffer, int i, int i2, int i3, int i4, int i5) {
        float f = i;
        float f2 = i2;
        float fMin = Math.min(f, (16.0f * f2) / 9.0f);
        float f3 = ((f - fMin) / 2.0f) + (((i5 * 0.24f) + 0.26f) * fMin);
        float f4 = fMin * 0.095f;
        int iClamp = clamp(Math.round(f3 - f4), 0, i - 2);
        int i6 = i - 1;
        int iClamp2 = clamp(Math.round(f3 + f4), iClamp + 1, i6);
        int iClamp3 = clamp(Math.round(0.25f * f2), 0, i2 - 2);
        int i7 = i2 - 1;
        int iClamp4 = clamp(Math.round(f2 * 0.7f), iClamp3 + 1, i7);
        int[] iArr = new int[560];
        if ((((long) i7) * ((long) i3)) + (((long) i6) * ((long) i4)) + 4 > byteBuffer.limit()) {
            return null;
        }
        for (int i8 = 0; i8 < 28; i8++) {
            for (int i9 = 0; i9 < 20; i9++) {
                int i10 = (((((iClamp4 - iClamp3) * i8) / 27) + iClamp3) * i3) + (((((iClamp2 - iClamp) * i9) / 19) + iClamp) * i4);
                iArr[(i8 * 20) + i9] = (((byteBuffer.get(i10) & UByte.MAX_VALUE) + (byteBuffer.get(i10 + 1) & UByte.MAX_VALUE)) + (byteBuffer.get(i10 + 2) & UByte.MAX_VALUE)) / 3;
            }
        }
        return iArr;
    }

    private void setDeckCount(int i) {
        this.deckCount = i;
        while (this.deckCosts.size() < i) {
            this.deckCosts.add(-1);
        }
        while (this.deckCosts.size() > i) {
            this.deckCosts.remove(this.deckCosts.size() - 1);
        }
        saveDeck();
        updateDeckLabel();
    }

    private boolean same(RectF rectF, RectF rectF2) {
        return ((Math.abs(rectF.left - rectF2.left) + Math.abs(rectF.top - rectF2.top)) + Math.abs(rectF.right - rectF2.right)) + Math.abs(rectF.bottom - rectF2.bottom) < 0.002f;
    }

    private static int clamp(int i, int i2, int i3) {
        return Math.max(i2, Math.min(i3, i));
    }

    private WindowManager.LayoutParams params(int i, int i2, int i3) {
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(i, i2, 2038, 8, -3);
        layoutParams.gravity = android.view.Gravity.START | i3;
        return layoutParams;
    }

    private void showDraftTools() {
        ArrayList<String> labels = new ArrayList<>();
        labels.add("Указать число карт в колоде");
        ArrayList<DraftCatalog.ClassStat> heroes = new ArrayList<>(catalog.classStats());
        for (DraftCatalog.ClassStat hero : heroes) labels.add(hero.label + "  " + percent(hero.winRate) + (hero.cached ? " • кэш" : ""));
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle("Колода и классы Арены")
            .setItems(labels.toArray(new String[0]), (d, index) -> {
                if (index > 0) {
                    currentClass = heroes.get(index - 1).id;
                    prefs.edit().putString("class", currentClass).apply();
                } else {
                    String[] counts = new String[31];
                    for (int i = 0; i < counts.length; i++) counts[i] = i + "/30";
                    android.app.AlertDialog picker = new android.app.AlertDialog.Builder(this)
                        .setTitle("Сколько карт уже выбрано?").setSingleChoiceItems(counts, deckCount, (p, value) -> {
                            setDeckCount(value); p.dismiss();
                        }).create();
                    picker.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    picker.show();
                }
            }).create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    private void showPanel() {
        if (this.panel != null) {
            return;
        }
        this.status = new TextView(this);
        this.status.setText("Загрузка локальной базы…");
        this.panel = new LinearLayout(this);
        this.panel.setOrientation(LinearLayout.VERTICAL);
        this.panel.setPadding(dp(8), dp(4), dp(8), dp(7));
        this.panel.setBackgroundColor(-267379414);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);
        this.panel.addView(linearLayout, new LinearLayout.LayoutParams(-1, -2));
        int i = 0;
        while (i < 3) {
            final int cellIndex=i;
            TextView textView = new TextView(this);
            int i2 = i + 1;
            textView.setText(String.valueOf(i2) + ". —");
            textView.setTextColor(-1);
            textView.setTextSize(12.0f);
            textView.setMaxLines(5);
            textView.setPadding(dp(7), dp(5), dp(7), dp(5));
            textView.setBackgroundColor(-14930883);
            textView.setOnClickListener(new View.OnClickListener() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda2
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    CaptureService.this.m1506lambda$7$ruarenahelperCaptureService(cellIndex, view);
                }
            });
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            if (i > 0) {
                layoutParams.leftMargin = dp(4);
            }
            linearLayout.addView(textView, layoutParams);
            this.cells.add(textView);
            i = i2;
        }
        this.wm.addView(this.panel, params(-1, -2, 48));
        syncPanelHeight();
        this.controls = new LinearLayout(this);
        this.controls.setOrientation(LinearLayout.VERTICAL);
        this.controls.setPadding(dp(4), dp(4), dp(4), dp(4));
        this.controls.setBackgroundColor(-535814870);
        this.deckLabel = new TextView(this);
        this.deckLabel.setOnClickListener(v -> { if (catalog != null) showDraftTools(); });
        this.deckLabel.setContentDescription("Исправить счётчик или выбрать класс");
        this.deckLabel.setTextColor(-1);
        this.deckLabel.setTextSize(14.0f);
        this.deckLabel.setGravity(17);
        this.deckLabel.setBackgroundColor(-14930883);
        this.controls.addView(this.deckLabel, new LinearLayout.LayoutParams(dp(64), dp(34)));
        updateDeckLabel();
        final Button buttonControl = control(this.controls, "Пауза");
        buttonControl.setOnClickListener(new View.OnClickListener() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                CaptureService.this.m1507lambda$8$ruarenahelperCaptureService(buttonControl, view);
            }
        });
        Button buttonControl2 = control(this.controls, "↺");
        buttonControl2.setContentDescription("Новый драфт");
        buttonControl2.setOnClickListener(new View.OnClickListener() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                CaptureService.this.m1494lambda$10$ruarenahelperCaptureService(view);
            }
        });
        Button buttonControl3 = control(this.controls, "×");
        buttonControl3.setContentDescription("Закрыть");
        buttonControl3.setOnClickListener(new View.OnClickListener() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                CaptureService.this.m1495lambda$11$ruarenahelperCaptureService(view);
            }
        });
        this.wm.addView(this.controls, params(-2, -2, 16));
    }

    
     void m1506lambda$7$ruarenahelperCaptureService(int i, View view) {
        onCellTap(i);
    }

    
     void m1507lambda$8$ruarenahelperCaptureService(Button button, View view) {
        lastPick.reset();
        this.paused = !this.paused;
        button.setText(this.paused ? "Пуск" : "Пауза");
        this.status.setText(this.paused ? "Пауза" : "Распознавание продолжено");
    }

    
     void m1494lambda$10$ruarenahelperCaptureService(View view) {
        resetDeck();
        if (this.currentMatches.isEmpty()) {
            this.status.setText("Новый драфт • колода 0/30");
        } else {
            renderCards(new ArrayList(this.currentMatches));
        }
    }

    
     void m1508lambda$9$ruarenahelperCaptureService(View view) {
        calibrate();
    }

    
     void m1495lambda$11$ruarenahelperCaptureService(View view) {
        stopSelf();
    }

    private void syncPanelHeight() {
        if (this.panel != null) {
            this.panel.post(new Runnable() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda13
                @Override // java.lang.Runnable
                public final void run() {
                    CaptureService.this.m1496lambda$12$ruarenahelperCaptureService();
                }
            });
        }
    }

    
     void m1496lambda$12$ruarenahelperCaptureService() {
        if (this.panel != null) {
            this.panelHeight = this.panel.getHeight();
        }
    }

    private Button control(LinearLayout linearLayout, String str) {
        Button button = new Button(this);
        button.setText(str);
        button.setTextSize(11.0f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(dp(64), dp(34));
        layoutParams.topMargin = dp(4);
        linearLayout.addView(button, layoutParams);
        return button;
    }

    private void updateDeckLabel() {
        if (this.deckLabel != null) {
            this.deckLabel.setText(String.valueOf(this.deckCount) + "/30");
        }
    }

    private void calibrate() {
        if (this.calibrating) {
            return;
        }
        this.calibrating = true;
        this.generation++;
        this.panel.setVisibility(View.GONE);
        if (this.controls != null) {
            this.controls.setVisibility(View.GONE);
        }
        this.calibration = new CalibrationView(this, new Function0() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda9
            @Override // kotlin.jvm.functions.Function0
            public final Object invoke() {
                return CaptureService.this.m1497lambda$13$ruarenahelperCaptureService();
            }
        });
        this.wm.addView(this.calibration, params(-1, -1, 48));
    }

    
     Unit m1497lambda$13$ruarenahelperCaptureService() {
        this.main.post(new Runnable() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                CaptureService.this.m1498lambda$14$ruarenahelperCaptureService();
            }
        });
        return Unit.INSTANCE;
    }

    
     void m1498lambda$14$ruarenahelperCaptureService() {
        if (this.calibration != null) {
            try {
                this.wm.removeView(this.calibration);
            } catch (Exception e) {
            }
        }
        this.calibration = null;
        this.calibrating = false;
        this.generation++;
        this.previousKey = "";
        this.stable = 0;
        this.panel.setVisibility(View.VISIBLE);
        if (this.controls != null) {
            this.controls.setVisibility(View.VISIBLE);
        }
        this.status.setText("Зоны сохранены");
    }

    private void renderHeroes(List<DraftCatalog.ClassStat> list, String str) {
        if (!this.heroMode) {
            resetDeck();
            this.heroMode = true;
        }
        this.activeHeroKey = str;
        this.currentMatches.clear();
        this.currentHeroes.clear();
        this.currentHeroes.addAll(list);
        DraftCatalog.ClassStat classStat = null;
        for (DraftCatalog.ClassStat classStat2 : list) {
            if (classStat2 != null && (classStat == null || classStat2.winRate > classStat.winRate)) {
                classStat = classStat2;
            }
        }
        this.status.setText("Лучший: " + classStat.label + " " + percent(classStat.winRate) + " • выберите класс");
        for (int i = 0; i < 3; i++) {
            TextView textView = this.cells.get(i);
            if (i < list.size() && list.get(i) != null) {
                DraftCatalog.ClassStat classStat3 = list.get(i);
                textView.setText(String.valueOf(classStat3 == classStat ? "★ " : "") + (i + 1) + ". " + classStat3.label + "\nВинрейт " + percent(classStat3.winRate) + " • " + classStat3.drafts + " драфтов" + (classStat3.cached ? " • кэш" : ""));
                textView.setBackgroundColor(classStat3 == classStat ? -15246528 : -14930883);
                textView.setEnabled(true);
            } else {
                textView.setText(String.valueOf(i + 1) + ". Класс не распознан");
                textView.setBackgroundColor(-13425878);
                textView.setEnabled(false);
            }
        }
        syncPanelHeight();
    }

    private void renderCards(List<Match> list) {
        DraftCatalog.PackageInfo packageInfoPackageFor;
        int i = 0;
        this.heroMode = false;
        this.activeHeroKey = "";
        this.currentHeroes.clear();
        this.currentMatches.clear();
        this.currentMatches.addAll(list);
        int i2 = 3;
        Integer[] numArr = new Integer[3];
        Integer[] numArr2 = new Integer[3];
        Integer[] numArr3 = new Integer[3];
        DraftCatalog.Details[] detailsArr = new DraftCatalog.Details[3];
        int i3 = -1;
        int i4 = 0;
        while (true) {
            if (i4 >= i2) {
                break;
            }
            Match match = i4 < list.size() ? list.get(i4) : null;
            if (match != null) {
                DraftCatalog.Details details = this.catalog.details(match.getCard().getName());
                detailsArr[i4] = details;
                if (details != null) {
                    DraftCatalog.PackageInfo packageInfoPackageFor2 = this.catalog.packageFor(details.name);
                    this.currentFirst[i4] = this.deckCount == 0;
                    boolean z = this.currentFirst[i4];
                    numArr2[i4] = this.catalog.score(details.name, this.currentClass);
                    numArr3[i4] = this.currentFirst[i4] ? DraftCatalog.packageCurve(details, packageInfoPackageFor2) : DraftCatalog.curveScore(details.cost, this.deckCosts);
                    numArr[i4] = ScoringPolicy.total(numArr2[i4], numArr3[i4], this.deckCount);
                    if (numArr[i4] != null && (i3 < 0 || numArr[i4].intValue() > numArr[i3].intValue())) {
                        i3 = i4;
                    }
                }
            }
            i4++;
            i2 = 3;
        }
        int i5 = 0;
        while (i5 < i2) {
            TextView textView = this.cells.get(i5);
            if ((i5 < list.size() ? list.get(i5) : null) == null || detailsArr[i5] == null) {
                textView.setText(String.valueOf(i5 + 1) + ". Не распознано\nНет оценки");
                textView.setBackgroundColor(-13425878);
                textView.setEnabled(false);
            } else {
                DraftCatalog.Details details2 = detailsArr[i5];
                StringBuilder sb = new StringBuilder();
                if (i5 == i3) {
                    sb.append("★ ");
                }
                sb.append(i5 + 1).append(". ").append(details2.name).append('\n');
                if (numArr[i5] != null) {
                    sb.append("Итог ").append(numArr[i5]).append(" • ");
                } else {
                    sb.append("Итог н/д • ");
                }
                sb.append("Сила ").append(numArr2[i5] == null ? "н/д" : numArr2[i5]);
                if(this.deckCount < 14) sb.append(" • Только сила (1–14)");
                else sb.append(numArr3[i5] == null ? " • Кривая н/д: только сила" : " • 70/30 • Кривая " + numArr3[i5] + (DraftCatalog.knownCosts(this.deckCosts) < this.deckCosts.size() ? " ≈ (" + DraftCatalog.knownCosts(this.deckCosts) + "/" + this.deckCosts.size() + " известно)" : ""));
                if (this.currentFirst[i5] && (packageInfoPackageFor = this.catalog.packageFor(details2.name)) != null && !packageInfoPackageFor.cards.isEmpty()) {
                    sb.append("\n+3: ");
                    for (int i6 = 0; i6 < packageInfoPackageFor.cards.size(); i6++) {
                        if (i6 > 0) {
                            sb.append(", ");
                        }
                        sb.append(packageInfoPackageFor.cards.get(i6).name);
                    }
                }
                textView.setText(sb.toString());
                textView.setBackgroundColor(i5 == i3 ? -15246528 : -14930883);
                textView.setEnabled(true);
            }
            i5++;
            i2 = 3;
        }
        String str = this.catalog.stat(this.currentClass) != null ? this.catalog.stat(this.currentClass).label : this.currentClass;
        Iterator<Match> it = list.iterator();
        while (it.hasNext()) {
            if (it.next() != null) {
                i++;
            }
        }
        String str2 = String.valueOf(str) + " • " + this.deckCount + "/30 • Карты " + i + "/3";
        if (i3 < 0) {
            str2 = String.valueOf(str2) + " • нет оценок";
        }
        this.status.setText(str2);
        syncPanelHeight();
    }

    private void onCellTap(int i) {
        Match match;
        DraftCatalog.Details details;
        long jElapsedRealtime = SystemClock.elapsedRealtime();
        if (jElapsedRealtime - this.lastTap < 450) {
            return;
        }
        this.lastTap = jElapsedRealtime;
        if (this.heroMode) {
            if (i >= this.currentHeroes.size()) {
                return;
            }
            DraftCatalog.ClassStat classStat = this.currentHeroes.get(i);
            if (classStat == null) return;
            this.currentClass = classStat.id;
            this.prefs.edit().putString("class", classStat.id).apply();
            resetDeck();
            this.status.setText("Выбран класс: " + classStat.label + " • колода 0/30");
            Toast.makeText(this, String.valueOf(classStat.label) + " выбран", Toast.LENGTH_SHORT).show();
            return;
        }
        if (i >= this.currentMatches.size() || (match = this.currentMatches.get(i)) == null || (details = this.catalog.details(match.getCard().getName())) == null) {
            return;
        }
        if (deckCount == 29) {
            manualLastPickAt = jElapsedRealtime;
            return;
        }
        if (trackedKey.equals(manuallyCountedOffer)) return;
        manuallyCountedOffer = trackedKey;
        this.motionActive = false;
        this.autoCandidate = -1;
        boolean z = this.currentFirst[i];
        addPick(details, z);
        Toast.makeText(this, z ? "Добавлен пакет: 4 карты" : "Добавлено вручную: " + details.name, Toast.LENGTH_SHORT).show();
        renderCards(new ArrayList(this.currentMatches));
    }

    private void addPick(DraftCatalog.Details details, boolean z) {
        int i;
        this.deckCosts.add(Integer.valueOf(details.cost));
        if (z) {
            DraftCatalog.PackageInfo packageInfoPackageFor = this.catalog.packageFor(details.name);
            int i2 = 0;
            if (packageInfoPackageFor != null) {
                Iterator<DraftCatalog.DeckCard> it = packageInfoPackageFor.cards.iterator();
                while (it.hasNext()) {
                    this.deckCosts.add(Integer.valueOf(it.next().cost));
                    i2++;
                }
            }
            while (i2 < 3) {
                this.deckCosts.add(-1);
                i2++;
            }
            i = this.deckCount + 4;
        } else {
            i = this.deckCount + 1;
        }
        this.deckCount = i;
        saveDeck();
        updateDeckLabel();
    }

    private void resetDeck() {
        lastPick.reset();
        manualLastPickAt = -10000L;
        this.manuallyCountedOffer = null;
        this.deckCount = 0;
        this.deckCosts.clear();
        saveDeck();
        updateDeckLabel();
        Arrays.fill(this.currentFirst, false);
        this.motionActive = false;
        this.autoCandidate = -1;
        this.trackedKey = "";
        resetMotion();
    }

    private void saveDeck() {
        StringBuilder sb = new StringBuilder();
        Iterator<Integer> it = this.deckCosts.iterator();
        while (it.hasNext()) {
            int iIntValue = it.next().intValue();
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(iIntValue);
        }
        this.prefs.edit().putInt("deckCount", this.deckCount).putString("deckCosts", sb.toString()).apply();
    }

    private void loadDeck() {
        this.deckCount = this.prefs.getInt("deckCount", 0);
        if (deckCount >= 30) {resetDeck();return;}
        String string = this.prefs.getString("deckCosts", "");
        if (string != null && !string.isEmpty()) {
            for (String str : string.split(",")) {
                try {
                    this.deckCosts.add(Integer.valueOf(Integer.parseInt(str)));
                } catch (NumberFormatException e) {
                }
            }
        }
        if (this.deckCount == 0) {
            this.deckCosts.clear();
        }
    }

    private static String percent(double d) {
        return String.valueOf(String.format(Locale.US, "%.1f", Double.valueOf(d)).replace('.', ',')) + "%";
    }

    private void fail(final Throwable th) {
        Log.e("ArenaHelper", "Capture initialization failed", th);
        this.main.post(new Runnable() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                CaptureService.this.m1499lambda$15$ruarenahelperCaptureService(th);
            }
        });
    }

    
     void m1499lambda$15$ruarenahelperCaptureService(Throwable th) {
        String str = "Ошибка: " + th.getClass().getSimpleName() + ": " + th.getMessage();
        if (this.status != null) {
            this.status.setText(str);
        }
        Toast.makeText(this, str, Toast.LENGTH_LONG).show();
    }

    @Override // android.app.Service
    public void onDestroy() {
        this.stopped = true;
        this.generation++;
        this.motionActive = false;
        if (this.calibration != null) {
            try {
                this.wm.removeView(this.calibration);
            } catch (Exception e) {
            }
        }
        if (this.panel != null) {
            try {
                this.wm.removeView(this.panel);
            } catch (Exception e2) {
            }
        }
        if (this.controls != null) {
            try {
                this.wm.removeView(this.controls);
            } catch (Exception e3) {
            }
        }
        if (this.worker != null) {
            this.worker.post(new Runnable() { // from class: ru.arenahelper.CaptureService$$ExternalSyntheticLambda10
                @Override // java.lang.Runnable
                public final void run() {
                    CaptureService.this.m1500lambda$16$ruarenahelperCaptureService();
                }
            });
        }
        this.thread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }

    
     void m1500lambda$16$ruarenahelperCaptureService() {
        try {
            if (this.reader != null) {
                this.reader.close();
            }
        } catch (Exception e) {
        }
        try {
            if (this.display != null) {
                this.display.release();
            }
        } catch (Exception e2) {
        }
        try {
            if (this.projection != null) {
                this.projection.stop();
            }
        } catch (Exception e3) {
        }
    }
}

package lt.arturas.tvpultas;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;

/** Standalone, one-tap Google/Android TV remote with a Google-TV-like layout. */
public class MainActivity extends Activity implements TvProtocol.Listener {
    private static final int BACKGROUND = Color.rgb(50,50,52);
    private static final int BUTTON = Color.rgb(27,27,27);
    private static final int GREEN = Color.rgb(104,211,143);
    private static final int GREY = Color.rgb(201,201,202);
    private TvProtocol tv;
    private LinearLayout root, header, body;
    private TextView deviceLabel, status;
    private EditText typing;
    private boolean keyboardShown;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener finder;
    private final Map<String,String> found = new LinkedHashMap<>();
    private String tvName = "Televizorius";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        tv = new TvProtocol(this,this);
        buildScreen(false);
        startDiscovery();
        if(tv.hasPairing()) tv.connectPrevious();
        else new android.os.Handler(getMainLooper()).postDelayed(this::chooseTv,400);
    }

    private int dp(float number) {
        return Math.round(getResources().getDisplayMetrics().density * number);
    }
    private static GradientDrawable shape(int color,int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
    private GradientDrawable borderShape(int color,int radius) {
        GradientDrawable d=shape(color,dp(radius));
        d.setStroke(dp(1.2f),Color.rgb(12,12,12));
        return d;
    }
    private TextView label(String value,int size,int color) {
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }
    private LinearLayout row() {
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }
    private void addWeighted(LinearLayout parent, View child,int height, float weight,int margin) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),weight);
        p.setMargins(dp(margin),0,dp(margin),0);
        parent.addView(child,p);
    }
    private TextView pill(String content,int bg,int font, int corner, Runnable onClick) {
        TextView t=label(content,font,GREY);
        t.setBackground(borderShape(bg,corner));
        t.setOnClickListener(v->onClick.run());
        t.setHapticFeedbackEnabled(true);
        return t;
    }
    private void buildScreen(boolean typingMode) {
        keyboardShown=typingMode;
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(12),dp(20),dp(16));
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);

        header=row();
        LinearLayout.LayoutParams headerP=new LinearLayout.LayoutParams(-1,dp(53));
        root.addView(header,headerP);

        TextView exit=label("×",39,Color.LTGRAY);
        exit.setBackground(shape(BUTTON,dp(40)));
        exit.setOnClickListener(v-> { if(keyboardShown) buildScreen(false); else finish(); });
        header.addView(exit,new LinearLayout.LayoutParams(dp(49),dp(49)));

        TextView device=label("▣  "+tvName+"   ⌄",20,Color.rgb(27,42,33));
        device.setSingleLine(true);device.setEllipsize(TextUtils.TruncateAt.END);
        device.setBackground(shape(GREEN,dp(26)));
        device.setPadding(dp(14),0,dp(9),0);
        device.setOnClickListener(v->chooseTv());
        LinearLayout.LayoutParams devP=new LinearLayout.LayoutParams(0,dp(51),1);
        devP.setMargins(dp(26),0,dp(20),0);
        header.addView(device,devP);
        deviceLabel=device;

        TextView power=label("⏻",31,GREY);
        power.setBackground(shape(BUTTON,dp(35)));
        power.setOnClickListener(v->sendKey(26));
        header.addView(power,new LinearLayout.LayoutParams(dp(49),dp(49)));

        status=label(tv.isConnected()?"Prisijungta":(tv.hasPairing()?"Jungiamasi…":"Pasirink televizorių"),12,Color.LTGRAY);
        status.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statP=new LinearLayout.LayoutParams(-1,dp(24));
        statP.topMargin=dp(8);
        root.addView(status,statP);

        body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyP=new LinearLayout.LayoutParams(-1,0,1);
        root.addView(body,bodyP);
        if(typingMode) createTypingScreen(); else createNavigationScreen();
    }
    private void createNavigationScreen() {
        FrameLayout area=new FrameLayout(this);
        body.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        final Dpad pad=new Dpad(this,key->sendKey(key));
        int widthPx=getResources().getDisplayMetrics().widthPixels;
        int circle=Math.min(widthPx-dp(44),dp(350));
        FrameLayout.LayoutParams dpadP=new FrameLayout.LayoutParams(circle,circle,Gravity.CENTER);
        area.addView(pad,dpadP);

        LinearLayout primary=row();
        LinearLayout.LayoutParams priP=new LinearLayout.LayoutParams(-1,dp(88));
        priP.bottomMargin=dp(26);
        body.addView(primary,priP);

        TextView back=pill("←",BUTTON,37,80,()->sendKey(4));
        addWeighted(primary,back,87,1,4);
        TextView home=pill("⌂",BUTTON,41,80,()->sendKey(3));
        addWeighted(primary,home,87,1.65f,5);
        TextView voice=pill("🎙",BUTTON,28,80,this::startVoice);
        addWeighted(primary,voice,87,1,4);

        LinearLayout sound=row();
        LinearLayout.LayoutParams volP=new LinearLayout.LayoutParams(-1,dp(65));
        volP.bottomMargin=dp(12);
        body.addView(sound,volP);

        TextView mute=pill("◀̸",BUTTON,29,35,()->sendKey(164));
        addWeighted(sound,mute,60,1,3);
        TextView down=pill("◁",BUTTON,30,35,()->sendKey(25));
        addWeighted(sound,down,60,1.4f,3);
        TextView up=pill("◀))",BUTTON,25,35,()->sendKey(24));
        addWeighted(sound,up,60,1.4f,3);

        TextView keyboard=label("⌨  Klaviatūra",15,GREY);
        keyboard.setGravity(Gravity.CENTER);
        keyboard.setOnClickListener(v->buildScreen(true));
        body.addView(keyboard,new LinearLayout.LayoutParams(-1,dp(36)));
    }

    private void createTypingScreen() {
        typing=new EditText(this);
        typing.setBackgroundColor(Color.TRANSPARENT);
        typing.setTextSize(39);
        typing.setTextColor(Color.rgb(225,225,225));
        typing.setHintTextColor(Color.rgb(139,139,142));
        typing.setHint("Rašykite telefonu,\ntekstas atsiras TV");
        typing.setGravity(Gravity.TOP|Gravity.START);
        typing.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        typing.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        typing.setPadding(dp(5),dp(62),dp(5),dp(5));
        body.addView(typing,new LinearLayout.LayoutParams(-1,0,1));
        typing.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence t,int start,int before,int count) {
                if(count>0) tv.text(t.subSequence(start,start+count).toString());
                if(before>count) for(int i=0;i<before-count;i++) sendKey(67);
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        typing.setOnEditorActionListener((view,id,event)->{ sendKey(66); return true; });

        TextView switcher=label("⠿   Grįžti į pultą",17,GREY);
        switcher.setBackground(borderShape(BACKGROUND,50));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(49));
        sp.bottomMargin=dp(6);
        body.addView(switcher,sp);
        switcher.setOnClickListener(v-> {
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(typing.getWindowToken(),0);
            buildScreen(false);
        });
        typing.requestFocus();
        typing.postDelayed(()-> {
            InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            ime.showSoftInput(typing,InputMethodManager.SHOW_IMPLICIT);
        },200);
    }
    private void sendKey(int code) {
        if(tv.isConnected()) {
            tv.key(code);
            getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } else Toast.makeText(this,"Dar neprisijungta prie TV",Toast.LENGTH_SHORT).show();
    }
    private void startVoice() {
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"Pasakykite tekstą TV paieškai");
        try { startActivityForResult(intent,500); }
        catch(Exception e) { Toast.makeText(this,"Balso atpažinimas nepasiekiamas",Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==500 && result==RESULT_OK && data!=null) {
            ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(words!=null && !words.isEmpty()) tv.text(words.get(0));
        }
    }

    private void chooseTv() {
        ArrayList<String> names=new ArrayList<>();
        ArrayList<String> hosts=new ArrayList<>();
        if(!tv.lastHost().isEmpty()){
            names.add("Paskutinis TV ("+tv.lastHost()+")");
            hosts.add(tv.lastHost());
        }
        for(Map.Entry<String,String> item:found.entrySet()) {
            if(!hosts.contains(item.getValue())) {
                names.add(item.getKey()+" ("+item.getValue()+")");
                hosts.add(item.getValue());
            }
        }
        names.add("Įvesti TV IP adresą");
        new AlertDialog.Builder(this)
            .setTitle("Pasirink televizorių")
            .setItems(names.toArray(new String[0]),(dialog,which)->{
                if(which==hosts.size()) enterIp();
                else askPairOrConnect(hosts.get(which),names.get(which));
            })
            .setNegativeButton("Atšaukti",null)
            .show();
    }
    private void enterIp() {
        EditText ip=new EditText(this);
        ip.setSingleLine(true);
        ip.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        ip.setHint("192.168.1.100");
        ip.setText(tv.lastHost());
        ip.setPadding(dp(20),dp(12),dp(20),dp(12));
        new AlertDialog.Builder(this)
            .setTitle("Televizoriaus IP adresas")
            .setView(ip)
            .setPositiveButton("Prijungti",(d,w)->askPairOrConnect(ip.getText().toString(),"TV"))
            .setNegativeButton("Atšaukti",null).show();
    }
    private void askPairOrConnect(String host,String name) {
        if(host.trim().isEmpty()) { enterIp(); return; }
        tvName=name.replaceAll("\\s*\\(.*","").trim();
        if(deviceLabel!=null) deviceLabel.setText("▣  "+tvName+"   ⌄");
        if(host.equals(tv.lastHost())&&tv.hasPairing()) {
            tv.connect(host);
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Suporuoti su TV?")
                .setMessage("Televizoriuje pasirodys 6 simbolių kodas. Jį įvesti reikės tik pirmą kartą.")
                .setPositiveButton("Poruoti",(d,w)->tv.beginPairing(host))
                .setNegativeButton("Atšaukti",null)
                .show();
        }
    }
    private void startDiscovery() {
        nsd=(NsdManager)getSystemService(NSD_SERVICE);
        if(nsd==null) return;
        finder=new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type,int code) {}
            @Override public void onStopDiscoveryFailed(String type,int code) {}
            @Override public void onServiceLost(NsdServiceInfo info) {
                runOnUiThread(()->found.remove(info.getServiceName()));
            }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if(!info.getServiceType().contains("androidtvremote2")) return;
                nsd.resolveService(info,new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i,int error) {}
                    @Override public void onServiceResolved(NsdServiceInfo service) {
                        if(service.getHost()!=null) runOnUiThread(()->found.put(
                            service.getServiceName(),service.getHost().getHostAddress()));
                    }
                });
            }
        };
        try { nsd.discoverServices("_androidtvremote2._tcp.",NsdManager.PROTOCOL_DNS_SD,finder); }
        catch(Exception ignored) {}
    }
    @Override public void onStatus(String message,boolean connected) {
        runOnUiThread(()->{
            if(status!=null) status.setText((connected?"● ":"○ ")+message);
            if(deviceLabel!=null) deviceLabel.setBackground(shape(connected?GREEN:Color.rgb(151,160,154),dp(26)));
        });
    }
    @Override public void onPairingCode() {
        runOnUiThread(()->{
            EditText pin=new EditText(this);
            pin.setSingleLine(true);
            pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
            pin.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            pin.setHint("ABC123");
            pin.setPadding(dp(25),dp(10),dp(25),dp(10));
            new AlertDialog.Builder(this).setTitle("Įvesk kodą iš televizoriaus")
                .setView(pin)
                .setPositiveButton("Suporuoti",(d,w)->tv.submitPairingCode(pin.getText().toString()))
                .setNegativeButton("Atšaukti",null).show();
        });
    }
    @Override public void onPaired() { runOnUiThread(()->Toast.makeText(this,"TV suporuotas",Toast.LENGTH_SHORT).show()); }
    @Override public void onTvKeyboard() { runOnUiThread(()->{if(!keyboardShown && tv.isConnected()) buildScreen(true);}); }
    @Override protected void onDestroy() {
        if(nsd!=null && finder!=null) try {nsd.stopServiceDiscovery(finder);}catch(Exception ignored){}
        tv.shutdown();
        super.onDestroy();
    }

    /** Round directional touch target drawn natively, at any screen density. */
    private static final class Dpad extends View {
        interface Press { void onKey(int code); }
        private final Press press;
        private final Paint p=new Paint(3);
        Dpad(Context context,Press listener) {super(context);press=listener;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            float rad=Math.min(cx,cy)-3;
            p.setColor(Color.rgb(10,10,10));p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx,cy,rad+3,p);
            p.setColor(Color.rgb(27,27,27));
            canvas.drawCircle(cx,cy,rad,p);
            p.setColor(Color.rgb(7,7,7)); canvas.drawCircle(cx,cy,rad*.405f,p);
            p.setColor(Color.rgb(66,66,66));canvas.drawCircle(cx,cy,rad*.395f,p);
            float at=rad*.705f, sz=rad*.082f;
            p.setColor(Color.rgb(71,71,71));p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(4,rad*.04f));
            p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            for(int i=0;i<4;i++){
                canvas.save();canvas.rotate(i*90,cx,cy);
                Path ar=new Path();
                ar.moveTo(cx-sz,cy-at+sz*.55f);
                ar.lineTo(cx,cy-at-sz*.4f);
                ar.lineTo(cx+sz,cy-at+sz*.55f);
                canvas.drawPath(ar,p);
                canvas.restore();
            }
            p.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN) return true;
            if(e.getAction()!=MotionEvent.ACTION_UP) return true;
            float x=e.getX()-getWidth()/2f,y=e.getY()-getHeight()/2f;
            float r=(float)Math.hypot(x,y), size=Math.min(getWidth(),getHeight())/2f;
            if(r>size) return true;
            int k;
            if(r<size*.4) k=23;
            else if(Math.abs(x)>Math.abs(y)) k=x>0?22:21;
            else k=y>0?20:19;
            press.onKey(k);
            performClick();
            return true;
        }
        @Override public boolean performClick() {super.performClick();return true;}
    }
}

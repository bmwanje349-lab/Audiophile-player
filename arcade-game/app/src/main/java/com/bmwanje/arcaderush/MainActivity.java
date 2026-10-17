package com.bmwanje.arcaderush;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(new GameView());
    }

    private static final class RoadObject {
        int lane;
        float z;
        boolean coin;
        RoadObject(int lane, float z, boolean coin) {
            this.lane = lane; this.z = z; this.coin = coin;
        }
    }

    private final class GameView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private final ArrayList<RoadObject> objects = new ArrayList<>();
        private final RectF rect = new RectF();
        private float w, h, horizon, playerLane = 1f, stripePhase = 0f;
        private float speed = .34f, distance = 0f, spawnTimer = .2f, coinTimer = .7f;
        private long lastFrame = 0;
        private int coins = 0, score = 0;
        private boolean crashed = false;
        private final int[] trafficColors = {
                Color.rgb(255, 85, 104), Color.rgb(255, 174, 52),
                Color.rgb(147, 91, 255), Color.rgb(50, 207, 181)
        };

        GameView() {
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        private int color(String hex) { return Color.parseColor(hex); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            w = getWidth(); h = getHeight(); horizon = h * .31f;
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? .016f : Math.min(.05f, (now - lastFrame) / 1_000_000_000f);
            lastFrame = now;
            if (!crashed) update(dt);
            drawWorld(c);
            drawObjects(c);
            drawPlayer(c);
            drawHud(c);
            if (crashed) drawGameOver(c);
            postInvalidateDelayed(16);
        }

        private void update(float dt) {
            distance += dt * (90f + speed * 60f);
            score = (int) distance + coins * 25;
            speed = Math.min(.62f, .34f + distance / 9500f);
            stripePhase = (stripePhase + dt * speed * 1.6f) % 1f;
            spawnTimer -= dt;
            coinTimer -= dt;
            if (spawnTimer <= 0) {
                int lane = random.nextInt(3);
                objects.add(new RoadObject(lane, 0f, false));
                spawnTimer = Math.max(.52f, 1.05f - distance / 18000f) + random.nextFloat() * .35f;
            }
            if (coinTimer <= 0) {
                objects.add(new RoadObject(random.nextInt(3), 0f, true));
                coinTimer = 1.0f + random.nextFloat() * 1.1f;
            }
            Iterator<RoadObject> it = objects.iterator();
            while (it.hasNext()) {
                RoadObject o = it.next();
                o.z += dt * speed;
                if (o.z > 1.12f) { it.remove(); continue; }
                if (Math.abs(o.z - .82f) < (o.coin ? .055f : .045f)
                        && o.lane == Math.round(playerLane)) {
                    if (o.coin) { coins++; score += 25; it.remove(); }
                    else { crashed = true; }
                }
            }
        }

        private void drawWorld(Canvas c) {
            p.setShader(new LinearGradient(0, 0, 0, horizon + 80,
                    color("#55D8FF"), color("#D6F7FF"), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, horizon + 80, p); p.setShader(null);
            p.setColor(color("#FFE36E")); c.drawCircle(w * .80f, h * .105f, w * .075f, p);
            // Soft distant clouds
            p.setColor(Color.WHITE);
            cloud(c, w*.15f, h*.12f, w*.09f);
            cloud(c, w*.55f, h*.18f, w*.065f);
            cloud(c, w*.92f, h*.22f, w*.075f);
            // Bright rolling hills
            p.setColor(color("#7BE06B"));
            Path hills = new Path(); hills.moveTo(0, horizon + 16);
            hills.cubicTo(w*.18f, horizon-h*.12f, w*.28f, horizon+h*.08f, w*.48f, horizon-2);
            hills.cubicTo(w*.70f, horizon-h*.14f, w*.82f, horizon+h*.05f, w, horizon-18);
            hills.lineTo(w, h); hills.lineTo(0, h); hills.close(); c.drawPath(hills,p);
            p.setColor(color("#38C96C"));
            c.drawRect(0, horizon + 34, w, h, p);
            // Road trapezoid and shoulder
            float topW = w*.11f, bottomW = w*.91f, cx = w*.5f;
            Path shoulder = new Path();
            shoulder.moveTo(cx-topW-9, horizon); shoulder.lineTo(cx+topW+9, horizon);
            shoulder.lineTo(cx+bottomW*.5f+12, h); shoulder.lineTo(cx-bottomW*.5f-12, h);
            shoulder.close(); p.setColor(color("#FFCF65")); c.drawPath(shoulder,p);
            Path road = new Path();
            road.moveTo(cx-topW, horizon); road.lineTo(cx+topW, horizon);
            road.lineTo(cx+bottomW*.5f, h); road.lineTo(cx-bottomW*.5f, h);
            road.close();
            p.setShader(new LinearGradient(0,horizon,0,h,color("#666F87"),color("#343B55"),Shader.TileMode.CLAMP));
            c.drawPath(road,p); p.setShader(null);
            // Road edge lines
            p.setColor(color("#FFFFFF")); p.setStrokeWidth(Math.max(2f,w*.008f));
            c.drawLine(cx-topW, horizon, cx-bottomW*.5f, h, p);
            c.drawLine(cx+topW, horizon, cx+bottomW*.5f, h, p);
            // Moving lane dashes, rendered in perspective
            for (int laneLine=1; laneLine<=2; laneLine++) {
                for (int i=0;i<10;i++) {
                    float z = (i/10f + stripePhase) % 1f;
                    float y = horizon + z*(h-horizon);
                    float roadWidth = topW*2 + (bottomW-topW*2)*z;
                    float x = cx - roadWidth*.5f + roadWidth*laneLine/3f;
                    float dashH = 3f + z*h*.026f;
                    p.setColor(i%2==0 ? color("#F9F8D7") : color("#D7D8E4"));
                    rect.set(x-1.4f-z*2, y-dashH*.5f, x+1.4f+z*2, y+dashH*.5f);
                    c.drawRoundRect(rect, 4, 4, p);
                }
            }
            // Roadside candy-coloured trees and shrubs
            for (int i=0;i<8;i++) {
                float z = (i/8f + stripePhase*.7f) % 1f;
                float y = horizon + z*(h-horizon);
                float rw = topW + (bottomW*.5f-topW)*z;
                float size = 5f + z*w*.095f;
                drawTree(c, cx-rw-size*.6f, y, size, i);
                drawTree(c, cx+rw+size*.6f, y, size, i+3);
            }
        }

        private void cloud(Canvas c, float x, float y, float r) {
            p.setColor(Color.WHITE);
            c.drawCircle(x-r*.55f,y,r*.48f,p); c.drawCircle(x,y-r*.22f,r*.62f,p);
            c.drawCircle(x+r*.52f,y,r*.42f,p);
            rect.set(x-r*.65f,y-r*.02f,x+r*.62f,y+r*.34f); c.drawRoundRect(rect,r*.2f,r*.2f,p);
        }

        private void drawTree(Canvas c, float x, float y, float s, int variant) {
            p.setColor(color("#8D573A"));
            rect.set(x-s*.11f,y-s*.2f,x+s*.11f,y+s*.28f); c.drawRoundRect(rect,s*.06f,s*.06f,p);
            int[] greens={color("#13B86B"),color("#2DCE82"),color("#70D94E"),color("#21B7A0")};
            p.setColor(greens[Math.abs(variant)%greens.length]);
            c.drawCircle(x,y-s*.36f,s*.42f,p); c.drawCircle(x-s*.25f,y-s*.22f,s*.3f,p);
            c.drawCircle(x+s*.25f,y-s*.2f,s*.3f,p);
            p.setColor(color("#FFE56B")); c.drawCircle(x+s*.14f,y-s*.39f,s*.07f,p);
        }

        private float roadCenterX(float lane, float z) {
            float roadWidth = w*.22f + (w*.455f-w*.11f)*z;
            return w*.5f + (lane-1f) * roadWidth/3f;
        }

        private void drawObjects(Canvas c) {
            for (RoadObject o : objects) {
                float y = horizon + o.z*(h-horizon);
                float x = roadCenterX(o.lane,o.z);
                if (o.coin) {
                    float r = w*(.012f + o.z*.027f);
                    p.setColor(color("#D98B00")); c.drawCircle(x+2,y-r*.7f,r*1.08f,p);
                    p.setShader(new LinearGradient(x-r,y-r,x+r,y+r,color("#FFF7A1"),color("#FFAA00"),Shader.TileMode.CLAMP));
                    c.drawCircle(x,y-r*.7f,r,p); p.setShader(null);
                    p.setColor(color("#FFF6C1")); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1,r*.2f));
                    c.drawCircle(x,y-r*.7f,r*.57f,p); p.setStyle(Paint.Style.FILL);
                } else {
                    float s = .25f + o.z*.90f;
                    drawCar(c,x,y, w*.12f*s, h*.065f*s, trafficColors[(o.lane+coins)%trafficColors.length], false);
                }
            }
        }

        private void drawPlayer(Canvas c) {
            float x = roadCenterX(playerLane,.82f);
            float y = h*.84f;
            // shadow
            p.setColor(0x550E1726); c.drawOval(x-w*.09f,y+h*.018f,x+w*.09f,y+h*.045f,p);
            drawCar(c,x,y,w*.205f,h*.125f,color("#12D6D0"),true);
        }

        private void drawCar(Canvas c,float x,float y,float cw,float ch,int body,boolean player) {
            // Wheels
            p.setColor(color("#20283B"));
            rect.set(x-cw*.52f,y-ch*.48f,x-cw*.30f,y+ch*.34f); c.drawRoundRect(rect,cw*.07f,cw*.07f,p);
            rect.set(x+cw*.30f,y-ch*.48f,x+cw*.52f,y+ch*.34f); c.drawRoundRect(rect,cw*.07f,cw*.07f,p);
            // Main sculpted body
            Path bodyPath=new Path();
            bodyPath.moveTo(x-cw*.43f,y+ch*.38f); bodyPath.lineTo(x-cw*.50f,y-ch*.08f);
            bodyPath.lineTo(x-cw*.30f,y-ch*.43f); bodyPath.quadTo(x,y-ch*.55f,x+cw*.30f,y-ch*.43f);
            bodyPath.lineTo(x+cw*.50f,y-ch*.08f); bodyPath.lineTo(x+cw*.43f,y+ch*.38f);
            bodyPath.quadTo(x,y+ch*.53f,x-cw*.43f,y+ch*.38f); bodyPath.close();
            p.setShader(new LinearGradient(x-cw*.5f,y,x+cw*.5f,y,lighten(body),body,Shader.TileMode.CLAMP));
            c.drawPath(bodyPath,p); p.setShader(null);
            // Windshield and hood highlights
            Path glass=new Path();
            glass.moveTo(x-cw*.26f,y-ch*.23f); glass.quadTo(x,y-ch*.36f,x+cw*.26f,y-ch*.23f);
            glass.lineTo(x+cw*.31f,y-ch*.02f); glass.lineTo(x-cw*.31f,y-ch*.02f); glass.close();
            p.setShader(new LinearGradient(x,y-ch*.35f,x,y, color("#BDEFFF"),color("#3978A8"),Shader.TileMode.CLAMP));
            c.drawPath(glass,p); p.setShader(null);
            p.setColor(color("#FFF8D6"));
            rect.set(x-cw*.39f,y+ch*.16f,x-cw*.22f,y+ch*.24f); c.drawRoundRect(rect,ch*.03f,ch*.03f,p);
            rect.set(x+cw*.22f,y+ch*.16f,x+cw*.39f,y+ch*.24f); c.drawRoundRect(rect,ch*.03f,ch*.03f,p);
            p.setColor(player?color("#FFB64D"):color("#FF526B"));
            rect.set(x-cw*.27f,y+ch*.34f,x-cw*.10f,y+ch*.40f); c.drawRoundRect(rect,ch*.02f,ch*.02f,p);
            rect.set(x+cw*.10f,y+ch*.34f,x+cw*.27f,y+ch*.40f); c.drawRoundRect(rect,ch*.02f,ch*.02f,p);
            if(player) {
                p.setColor(0x99FFFFFF); p.setStrokeWidth(Math.max(2,cw*.025f));
                c.drawLine(x-cw*.04f,y-ch*.45f,x-cw*.04f,y-ch*.28f,p);
            }
        }

        private int lighten(int col) {
            return Color.rgb(Math.min(255,Color.red(col)+55),Math.min(255,Color.green(col)+55),Math.min(255,Color.blue(col)+55));
        }

        private void drawHud(Canvas c) {
            p.setShader(new LinearGradient(0,0,0,h*.13f,0xCC12264A,0x0012264A,Shader.TileMode.CLAMP));
            c.drawRect(0,0,w,h*.14f,p); p.setShader(null);
            p.setTextAlign(Paint.Align.LEFT); p.setTypeface(android.graphics.Typeface.create("sans-serif-black",1));
            p.setColor(Color.WHITE); p.setTextSize(w*.052f); c.drawText("TURBO TRAILS",w*.055f,h*.065f,p);
            p.setTextSize(w*.034f); p.setColor(color("#CFF9FF")); c.drawText("ARCADE RUSH",w*.06f,h*.103f,p);
            p.setTextAlign(Paint.Align.RIGHT); p.setTextSize(w*.047f); p.setColor(color("#FFF2A2"));
            c.drawText("★ "+score,w*.95f,h*.064f,p);
            p.setTextSize(w*.04f); p.setColor(color("#FFE36E")); c.drawText("● "+coins,w*.95f,h*.105f,p);
            // Touch steering pads
            float y=h*.925f, r=w*.105f;
            p.setColor(0x77364D70); c.drawCircle(w*.19f,y,r,p); c.drawCircle(w*.81f,y,r,p);
            p.setColor(Color.WHITE);
            Path left=new Path(); left.moveTo(w*.22f,y-r*.45f); left.lineTo(w*.09f,y); left.lineTo(w*.22f,y+r*.45f); left.close(); c.drawPath(left,p);
            Path right=new Path(); right.moveTo(w*.78f,y-r*.45f); right.lineTo(w*.91f,y); right.lineTo(w*.78f,y+r*.45f); right.close(); c.drawPath(right,p);
            p.setTextAlign(Paint.Align.CENTER); p.setTextSize(w*.027f); p.setColor(0xEEFFFFFF);
            c.drawText("STEER LEFT",w*.19f,y+r*1.38f,p); c.drawText("STEER RIGHT",w*.81f,y+r*1.38f,p);
        }

        private void drawGameOver(Canvas c) {
            p.setColor(0x990C1730); c.drawRect(0,0,w,h,p);
            float boxW=w*.82f, boxH=h*.31f, left=(w-boxW)/2f, top=h*.34f;
            p.setShader(new LinearGradient(left,top,left+boxW,top+boxH,color("#6847D8"),color("#187ACB"),Shader.TileMode.CLAMP));
            rect.set(left,top,left+boxW,top+boxH); c.drawRoundRect(rect,w*.06f,w*.06f,p); p.setShader(null);
            p.setTextAlign(Paint.Align.CENTER); p.setTypeface(android.graphics.Typeface.create("sans-serif-black",1));
            p.setColor(Color.WHITE); p.setTextSize(w*.085f); c.drawText("WIPEOUT!",w*.5f,top+boxH*.29f,p);
            p.setTextSize(w*.05f); p.setColor(color("#FFF2A2")); c.drawText("SCORE  "+score,w*.5f,top+boxH*.48f,p);
            p.setTextSize(w*.04f); p.setColor(Color.WHITE); c.drawText("COINS  "+coins,w*.5f,top+boxH*.63f,p);
            p.setColor(color("#FFDF64")); rect.set(left+boxW*.15f,top+boxH*.72f,left+boxW*.85f,top+boxH*.91f);
            c.drawRoundRect(rect,w*.04f,w*.04f,p);
            p.setColor(color("#28304C")); p.setTextSize(w*.044f);
            c.drawText("TAP TO RACE AGAIN",w*.5f,top+boxH*.845f,p);
        }

        private void restart() {
            objects.clear(); playerLane=1f; speed=.34f; distance=0; score=0; coins=0;
            spawnTimer=.2f; coinTimer=.7f; stripePhase=0; crashed=false; lastFrame=0;
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction()==MotionEvent.ACTION_DOWN) {
                if (crashed) { restart(); return true; }
                if (e.getY()>h*.54f) {
                    if(e.getX()<w*.5f) playerLane=Math.max(0,playerLane-1);
                    else playerLane=Math.min(2,playerLane+1);
                    invalidate(); return true;
                }
                return true;
            }
            return true;
        }
    }
}

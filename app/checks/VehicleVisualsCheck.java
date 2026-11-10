import kr.routine.app.ui.VehicleVisuals;

/** Checks design tokens, not Android font rasterization or layout. */
public final class VehicleVisualsCheck {
    private static int assertions;
    private static void expect(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
        assertions++;
    }
    private static void contrast(int text,int surface,String label) {
        double ratio=VehicleVisuals.contrast(text,surface);
        expect(ratio>=4.5,label+" contrast "+ratio+" < 4.5");
    }
    public static void main(String[] args) {
        expect(VehicleVisuals.screenAfterState("home",false,true,false).equals("guide"),"explicit confirm enters guide");
        expect(VehicleVisuals.screenAfterState("guide",true,false,false).equals("home"),"pre-send veto returns to current-route state");
        expect(VehicleVisuals.screenAfterState("settings",false,true,false).equals("settings"),"recreation preserves saved settings");
        expect(VehicleVisuals.screenAfterState("onboard2",false,true,false).equals("onboard2"),"recreation preserves saved registration page");
        expect(VehicleVisuals.screenAfterState("settings",true,true,true).equals("guide"),"motion closes settings on an issued trip");
        expect(VehicleVisuals.screenAfterState("picker",false,false,true).equals("home"),"motion closes destination editing");
        for(var p:new VehicleVisuals.Palette[]{VehicleVisuals.DAY,VehicleVisuals.NIGHT}) {
            for(int surface:new int[]{p.background(),p.surface(),p.raised()}) {
                contrast(p.text(),surface,"main text");
                contrast(p.secondary(),surface,"secondary text");
                contrast(p.muted(),surface,"disabled text");
            }
            contrast(p.primaryText(),p.primary(),"primary button");
            contrast(p.text(),p.selected(),"selected preference and history row");
            contrast(p.warningText(),p.warning(),"warning banner");
            contrast(p.warningText(),p.background(),"delay warning");
        }
        expect(!new VehicleVisuals.Metrics(1681,1210,1,1).compact(),"reference full window");
        expect(new VehicleVisuals.Metrics(834,1210,1,1).compact(),"reference split window");
        expect(new VehicleVisuals.Metrics(1000,1210,1,1).compact(),"split boundary inclusive");
        expect(!new VehicleVisuals.Metrics(1001,1210,1,1).compact(),"full boundary exclusive");
        for(int width:new int[]{320,600,834,1000,1001,1681})
            for(int height:new int[]{480,700,1210})
                for(float density:new float[]{1f,1.5f,2f})
                    for(float scale:new float[]{1f,1.3f,2f}) {
                        var m=new VehicleVisuals.Metrics(width,height,density,density*scale);
                        expect(m.text(20)>=30*density*scale,"smallest text keeps 30sp minimum");
                        expect(m.touch(40)>=76*density,"smallest touch keeps 76dp minimum");
                        expect(m.destinationText()>=30*density*scale,"destination keeps font minimum");
                        expect(m.primaryHeight()>=76*density,"primary touch minimum");
                        expect(m.etaText()>=m.destinationText(),"ETA retains hierarchy");
                        if(width<=1000) {
                            expect(m.stackButtons(),"split action reflow");
                            expect(m.stackPreferences(),"split preference reflow");
                        }
                    }
        System.out.println("VehicleVisualsCheck PASS "+assertions+" assertions; Android rendering NOT verified");
    }
}

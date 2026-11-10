package kr.routine.desktop;

import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Font;

/** Local PC prototype using the Android app's shared recommendation core. */
public final class DesktopApp {
    public static void main(String[] args) {
        Path data = args.length == 2 && "--data".equals(args[0])
            ? Path.of(args[1]) : Path.of("demo-data", "desktop.properties");
        SwingUtilities.invokeLater(() -> {
            try {
                installFonts();
                DesktopPanel panel = new DesktopPanel(data);
                JFrame frame = new JFrame("일상 주행 도우미 · PC 프로토타입");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setContentPane(panel);
                frame.setSize(1200, 840);
                frame.setMinimumSize(new java.awt.Dimension(1100, 780));
                frame.setLocationByPlatform(true);
                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override public void windowClosed(java.awt.event.WindowEvent event) { panel.close(); }
                });
                frame.setVisible(true);
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null, "저장소를 열지 못했습니다. 기존 파일은 변경하지 않았습니다.\n"
                    + error.getMessage(), "실행 확인", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public static void installFonts() {
        Font font = new Font("Malgun Gothic", Font.PLAIN, 15);
        for (Object key : UIManager.getDefaults().keySet().toArray())
            if (key.toString().endsWith("font")) UIManager.put(key, font);
    }
}

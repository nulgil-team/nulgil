package kr.routine.desktop;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import kr.routine.core.*;
import kr.routine.core.Model.*;

/** Runs real Swing button handlers on the EDT; does not open a native window. */
public final class DesktopUiCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("A fresh evidence directory is required");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        if (Files.exists(output.resolve("desktop.properties"))) throw new IllegalArgumentException("Use a new output folder");
        SwingUtilities.invokeAndWait(() -> {
            try { run(output); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        System.out.println("PASS " + checks + " desktop UI checks");
    }

    private static void run(Path output) throws Exception {
        DesktopApp.installFonts();
        DesktopPanel panel = new DesktopPanel(output.resolve("desktop.properties"));
        require(!panel.confirmButton.isEnabled(), "empty store must not start guidance");
        panel.seedButton.doClick();
        require(panel.store.selections().isEmpty(), "seed requires recording consent");
        panel.consent.doClick();
        panel.seedButton.doClick();
        require(panel.store.selections().size() >= 3, "consented examples persist");
        require(panel.confirmButton.isEnabled(), "registered sample produces a confirmable recommendation");
        require(panel.reasonText.getText().contains("3일"), "history mode learns from three distinct dates");
        panel.scenario.setSelectedIndex(1);
        require(panel.etaLabel.getText().contains("44"), "congestion changes the computed estimate");
        require(panel.trafficText.getText().contains("15"), "traffic comparison uses the median baseline");
        save(panel, output.resolve("01_congestion.png"));
        panel.scenario.setSelectedIndex(2);
        require(panel.etaLabel.getText().equals("교통 정보 조회 실패")
            && panel.trafficText.getText().contains("누락"), "missing traffic has no invented ETA");
        save(panel, output.resolve("02_missing_traffic.png"));
        panel.scenario.setSelectedIndex(0);
        panel.parked.doClick();
        require(!panel.confirmButton.isEnabled(), "driving state disables confirmation");
        panel.parked.doClick();
        panel.dismissButton.doClick();
        require(panel.gateway.startCount() == 0, "dismiss must not call guidance");
        panel.newTripButton.doClick();
        panel.preference.setSelectedIndex(2);
        int before = panel.store.selections().size();
        panel.confirmButton.doClick();
        require(panel.gateway.startCount() == 1, "explicit confirmation issues one request");
        require(panel.store.selections().size() == before + 1, "verified simulated guidance records one selection");
        panel.confirmButton.doClick();
        require(panel.gateway.startCount() == 1, "second click cannot duplicate a request");
        require(!panel.preference.isEnabled(), "completed session cannot silently change route preference");
        save(panel, output.resolve("03_confirmed.png"));
        panel.close();
        DesktopPanel reopened = new DesktopPanel(output.resolve("desktop.properties"));
        require(reopened.store.selections().size() == before + 1, "selection survives reopening");
        require(reopened.preference.getSelectedIndex() == 2, "preference survives reopening");
        reopened.day.setSelectedIndex(6);
        require(!reopened.confirmButton.isEnabled(), "another weekday is not forced into a recommendation");
        reopened.day.setSelectedIndex(2);
        reopened.time.setText("21:00"); reopened.time.postActionEvent();
        require(!reopened.confirmButton.isEnabled(), "another time is not forced into a recommendation");
        reopened.time.setText("08:00"); reopened.time.postActionEvent();
        reopened.clearButton.doClick();
        require(reopened.store.selections().isEmpty() && reopened.store.estimates().isEmpty(), "clear removes only app history");
        require(!reopened.store.rules().isEmpty(), "explicit rules remain after clearing history");
        reopened.consent.doClick();
        reopened.historyOnly.setSelected(false);
        int withoutConsent = reopened.store.selections().size();
        reopened.newTripButton.doClick();
        require(reopened.confirmButton.isEnabled(), "manual rule remains usable with collection disabled");
        reopened.confirmButton.doClick();
        require(reopened.store.selections().size() == withoutConsent, "disabled collection does not save selection");
        reopened.newTripButton.doClick();
        reopened.gateway.setFailNext(true);
        reopened.confirmButton.doClick();
        require(!reopened.confirmButton.isEnabled(), "failed request is not retried automatically");
        require(reopened.statusText.getText().contains("미확인"), "failed request is shown as unconfirmed");
        require(reopened.store.selections().size() == withoutConsent, "failed request is not learned as a selection");
        save(reopened, output.resolve("04_unconfirmed.png"));
        reopened.close();
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static void save(DesktopPanel panel, Path file) throws IOException {
        if (Files.exists(file)) throw new IOException("Existing image preserved: " + file);
        panel.setSize(1200, 800); layout(panel);
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        panel.printAll(graphics); graphics.dispose();
        ImageIO.write(image, "png", file.toFile());
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component c : container.getComponents()) if (c instanceof Container child) layout(child);
    }
}

/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.BuildStatisticsResource.BuildStatistics;
import ai.mnemosyne.cisys.BuildStatisticsResource.Failure;
import ai.mnemosyne.cisys.BuildStatisticsResource.FlakyRevision;
import ai.mnemosyne.cisys.BuildStatisticsResource.HistogramBucket;
import ai.mnemosyne.cisys.BuildStatisticsResource.Row;
import ai.mnemosyne.cisys.BuildStatisticsResource.SlowRow;
import ai.mnemosyne.cisys.BuildStatisticsResource.TrendPoint;
import ai.mnemosyne.cisys.BuildStatisticsResource.Trends;
import ai.mnemosyne.cisys.UserResource.ProjectRef;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.enterprise.context.ApplicationScoped;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import java.util.function.ToDoubleFunction;
import javax.imageio.ImageIO;

/** Renders {@link BuildStatistics} as a PDF of summary tables, trend charts and trend tables. */
@ApplicationScoped
public class BuildStatisticsPdf {
  private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
  private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
  private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
  private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9);
  private static final Color HEAD_FILL = new Color(235, 235, 235);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter SHORT =
      DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneOffset.UTC);
  private static final List<String> COLUMNS =
      List.of("Builds", "Passed", "Failed", "Cancelled", "Active", "Success", "Avg duration");

  // Chart geometry and palette, matching the Reports page.
  private static final int CHART_W = 1000;
  private static final int CHART_H = 300;
  private static final Color GRID = new Color(224, 227, 232);
  private static final Color AXIS = new Color(190, 196, 204);
  private static final Color TICK = new Color(90, 96, 107);
  private static final Color[] SERIES = {
    new Color(23, 106, 74),
    new Color(180, 83, 9),
    new Color(124, 58, 237),
    new Color(14, 116, 144),
    new Color(185, 28, 28),
    new Color(77, 124, 15)
  };

  /** The PDF bytes. */
  public byte[] render(BuildStatistics s) {
    return render(s, null);
  }

  /** The PDF bytes, including the trend summary when {@code trends} is not null. */
  public byte[] render(BuildStatistics s, Trends trends) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
    PdfWriter.getInstance(doc, out);
    doc.open();
    doc.add(new Paragraph("Build report", TITLE));
    doc.add(
        new Paragraph(
            scope(s) + " · " + period(s.period()) + " · " + TIME.format(s.generatedAt()), BODY));
    section(doc, "Totals", "", List.of(s.totals()));
    section(doc, "Projects", "Project", s.byProject());
    section(
        doc,
        "Triggers",
        "Trigger",
        s.byTrigger().stream().map(BuildStatisticsPdf::trigger).toList());
    if (!s.byBranch().isEmpty()) {
      section(doc, "Branches", "Branch", s.byBranch());
    }
    failures(doc, s.recentFailures());
    trends(doc, trends);
    doc.close();
    return out.toByteArray();
  }

  private static void trends(Document doc, Trends t) {
    if (t == null) {
      return;
    }
    // Trends are a visual section: start it on a fresh page so the charts are
    // not squeezed between summary tables.
    doc.newPage();
    doc.add(heading("Trends"));
    doc.add(
        new Paragraph(
            bucket(t.bucket())
                + " buckets · "
                + TIME.format(t.from())
                + " to "
                + TIME.format(t.to())
                + (t.truncated() ? " · newest buckets shown" : ""),
            BODY));
    charts(doc, t);
    bucketTable(doc, t.buckets());
    histogram(doc, t.histogram());
    slow(doc, "Slowest projects", t.slowestProjects());
    slow(doc, "Slowest branches", t.slowestBranches());
    flaky(doc, t.flakyRevisions());
  }

  private static void bucketTable(Document doc, List<TrendPoint> buckets) {
    if (buckets.isEmpty()) {
      emptySection(doc, "Volume by bucket", "No builds");
      return;
    }
    doc.add(heading("Volume by bucket"));
    PdfPTable table = new PdfPTable(new float[] {2, 1, 1, 1, 1, 1, 1.4f, 1.2f, 1.2f, 1.2f});
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    List.of(
            "Bucket",
            "Builds",
            "Passed",
            "Failed",
            "Cancelled",
            "Success",
            "Avg",
            "P50",
            "P95",
            "Queue")
        .forEach(c -> table.addCell(head(c)));
    for (TrendPoint b : buckets) {
      table.addCell(cell(TIME.format(b.bucket()), Element.ALIGN_LEFT));
      table.addCell(cell(Long.toString(b.builds()), Element.ALIGN_RIGHT));
      table.addCell(cell(Long.toString(b.passed()), Element.ALIGN_RIGHT));
      table.addCell(cell(Long.toString(b.failed()), Element.ALIGN_RIGHT));
      table.addCell(cell(Long.toString(b.cancelled()), Element.ALIGN_RIGHT));
      table.addCell(cell(percent(b.successRate()), Element.ALIGN_RIGHT));
      table.addCell(cell(duration(b.averageDurationSeconds()), Element.ALIGN_RIGHT));
      table.addCell(cell(duration(b.p50DurationSeconds()), Element.ALIGN_RIGHT));
      table.addCell(cell(duration(b.p95DurationSeconds()), Element.ALIGN_RIGHT));
      table.addCell(cell(duration(b.averageQueueSeconds()), Element.ALIGN_RIGHT));
    }
    doc.add(table);
  }

  private static void histogram(Document doc, List<HistogramBucket> histogram) {
    doc.add(heading("Duration histogram"));
    PdfPTable table = new PdfPTable(new float[] {2, 1});
    table.setWidthPercentage(50);
    table.setKeepTogether(true);
    table.setHeaderRows(1);
    List.of("Duration", "Builds").forEach(c -> table.addCell(head(c)));
    for (HistogramBucket b : histogram) {
      table.addCell(cell(b.label(), Element.ALIGN_LEFT));
      table.addCell(cell(Long.toString(b.builds()), Element.ALIGN_RIGHT));
    }
    doc.add(table);
  }

  private static void slow(Document doc, String title, List<SlowRow> rows) {
    if (rows.isEmpty()) {
      emptySection(doc, title, "No builds");
      return;
    }
    doc.add(heading(title));
    PdfPTable table = new PdfPTable(new float[] {3, 1, 1.4f});
    table.setWidthPercentage(60);
    table.setKeepTogether(true);
    table.setHeaderRows(1);
    List.of("Name", "Builds", "Avg").forEach(c -> table.addCell(head(c)));
    for (SlowRow r : rows) {
      table.addCell(cell(r.label(), Element.ALIGN_LEFT));
      table.addCell(cell(Long.toString(r.builds()), Element.ALIGN_RIGHT));
      table.addCell(cell(duration(r.averageSeconds()), Element.ALIGN_RIGHT));
    }
    doc.add(table);
  }

  private static void flaky(Document doc, List<FlakyRevision> rows) {
    if (rows.isEmpty()) {
      emptySection(doc, "Flaky revisions", "No flaky revisions");
      return;
    }
    doc.add(heading("Flaky revisions"));
    PdfPTable table = new PdfPTable(new float[] {2, 2, 1.4f, 1, 1, 2});
    table.setWidthPercentage(100);
    table.setKeepTogether(true);
    table.setHeaderRows(1);
    List.of("Project", "Branch", "Commit", "Failed", "Passed", "Last")
        .forEach(c -> table.addCell(head(c)));
    for (FlakyRevision r : rows) {
      table.addCell(cell(r.project(), Element.ALIGN_LEFT));
      table.addCell(cell(r.branch(), Element.ALIGN_LEFT));
      table.addCell(
          cell(
              r.revision() == null
                  ? null
                  : r.revision().substring(0, Math.min(7, r.revision().length())),
              Element.ALIGN_LEFT));
      table.addCell(cell(Long.toString(r.failures()), Element.ALIGN_RIGHT));
      table.addCell(cell(Long.toString(r.passes()), Element.ALIGN_RIGHT));
      table.addCell(cell(time(r.lastAt()), Element.ALIGN_LEFT));
    }
    doc.add(table);
  }

  private static String bucket(String bucket) {
    return switch (bucket) {
      case "week" -> "Weekly";
      case "day" -> "Daily";
      default -> bucket;
    };
  }

  private static String scope(BuildStatistics s) {
    if (s.projectId() == null) {
      return "All projects";
    }
    return s.projects().stream()
        .filter(p -> p.id().equals(s.projectId()))
        .map(ProjectRef::name)
        .findFirst()
        .orElse("");
  }

  static String period(String period) {
    return switch (period) {
      case "7d" -> "Last 7 days";
      case "30d" -> "Last 30 days";
      case "365d" -> "Last year";
      default -> "All time";
    };
  }

  private static Row trigger(Row r) {
    String label =
        switch (r.key()) {
          case "POLL" -> "Poll";
          case "PULL_REQUEST" -> "Pull request";
          default -> "Manual";
        };
    return new Row(
        r.key(),
        label,
        r.builds(),
        r.passed(),
        r.failed(),
        r.cancelled(),
        r.active(),
        r.successRate(),
        r.averageSeconds(),
        r.lastBuildAt());
  }

  private static void section(Document doc, String title, String first, List<Row> rows) {
    if (rows.isEmpty() || rows.getFirst().builds() == 0) {
      emptySection(doc, title, "No builds");
      return;
    }
    doc.add(heading(title));
    boolean labelled = !first.isEmpty();
    PdfPTable t = new PdfPTable(COLUMNS.size() + (labelled ? 1 : 0));
    t.setWidthPercentage(100);
    t.setHeaderRows(1);
    if (labelled) {
      t.addCell(head(first));
    }
    COLUMNS.forEach(c -> t.addCell(head(c)));
    for (Row r : rows) {
      if (labelled) {
        t.addCell(cell(r.label().isEmpty() ? "—" : r.label(), Element.ALIGN_LEFT));
      }
      for (String v :
          List.of(
              Long.toString(r.builds()),
              Long.toString(r.passed()),
              Long.toString(r.failed()),
              Long.toString(r.cancelled()),
              Long.toString(r.active()),
              percent(r.successRate()),
              duration(r.averageSeconds()))) {
        t.addCell(cell(v, Element.ALIGN_RIGHT));
      }
    }
    doc.add(t);
  }

  private static void failures(Document doc, List<Failure> failures) {
    doc.add(heading("Recent failures"));
    if (failures.isEmpty()) {
      doc.add(new Paragraph("No builds", BODY));
      return;
    }
    PdfPTable t = new PdfPTable(new float[] {3, 1, 2, 1.2f, 2});
    t.setWidthPercentage(100);
    List.of("Project", "Build", "Branch", "Commit", "Finished").forEach(c -> t.addCell(head(c)));
    for (Failure f : failures) {
      t.addCell(cell(f.project(), Element.ALIGN_LEFT));
      t.addCell(cell("#" + f.number(), Element.ALIGN_RIGHT));
      t.addCell(cell(f.branch(), Element.ALIGN_LEFT));
      t.addCell(
          cell(
              f.revision() == null
                  ? null
                  : f.revision().substring(0, Math.min(7, f.revision().length())),
              Element.ALIGN_LEFT));
      t.addCell(cell(time(f.finishedAt()), Element.ALIGN_LEFT));
    }
    doc.add(t);
  }

  private static Paragraph heading(String text) {
    Paragraph p = new Paragraph(text, HEADING);
    p.setSpacingBefore(14);
    p.setSpacingAfter(6);
    return p;
  }

  /**
   * A title and its empty-state message in one paragraph, so a page break never leaves the title
   * stranded at the bottom of a page.
   */
  private static void emptySection(Document doc, String title, String message) {
    Paragraph p = new Paragraph();
    p.setSpacingBefore(14);
    p.setSpacingAfter(6);
    p.add(new Chunk(title + "\n", HEADING));
    p.add(new Chunk(message, BODY));
    doc.add(p);
  }

  private static PdfPCell head(String text) {
    PdfPCell c = new PdfPCell(new Phrase(text, HEAD));
    c.setBackgroundColor(HEAD_FILL);
    c.setPadding(4);
    return c;
  }

  private static PdfPCell cell(String text, int align) {
    PdfPCell c = new PdfPCell(new Phrase(text == null || text.isEmpty() ? "—" : text, BODY));
    c.setHorizontalAlignment(align);
    c.setPadding(4);
    return c;
  }

  static String percent(Double rate) {
    return rate == null ? "—" : String.format(Locale.ROOT, "%.0f%%", rate * 100);
  }

  static String duration(Double seconds) {
    if (seconds == null) {
      return "—";
    }
    long s = Math.round(seconds), m = s / 60;
    return m > 0 ? m + "m " + s % 60 + "s" : s + "s";
  }

  /** A single line or bar series for the PDF charts. */
  private record Series(String label, double[] values, Color color) {}

  private static void charts(Document doc, Trends t) {
    List<TrendPoint> buckets = t.buckets();
    if (buckets.isEmpty()) {
      emptySection(doc, "Charts", "No builds");
      return;
    }
    List<String> labels = buckets.stream().map(b -> SHORT.format(b.bucket())).toList();
    addChart(
        doc,
        "Builds over time",
        renderChart(
            labels,
            List.of(new Series("Builds", values(buckets, b -> b.builds()), SERIES[0])),
            false,
            true,
            v -> Long.toString(Math.round(v))));
    addChart(
        doc,
        "Success rate",
        renderChart(
            labels,
            List.of(
                new Series(
                    "Success rate",
                    values(buckets, b -> b.successRate() == null ? 0 : b.successRate() * 100),
                    SERIES[0])),
            false,
            false,
            v -> Math.round(v) + "%"));
    addChart(
        doc,
        "Durations",
        renderChart(
            labels,
            List.of(
                new Series(
                    "Average",
                    values(
                        buckets,
                        b -> b.averageDurationSeconds() == null ? 0 : b.averageDurationSeconds()),
                    SERIES[0]),
                new Series(
                    "P50",
                    values(
                        buckets, b -> b.p50DurationSeconds() == null ? 0 : b.p50DurationSeconds()),
                    SERIES[1]),
                new Series(
                    "P95",
                    values(
                        buckets, b -> b.p95DurationSeconds() == null ? 0 : b.p95DurationSeconds()),
                    SERIES[2])),
            false,
            false,
            v -> duration(v)));
    addChart(
        doc,
        "Queue wait",
        renderChart(
            labels,
            List.of(
                new Series(
                    "Average queue",
                    values(
                        buckets,
                        b -> b.averageQueueSeconds() == null ? 0 : b.averageQueueSeconds()),
                    SERIES[3])),
            false,
            false,
            v -> duration(v)));
    addChart(
        doc,
        "Builds by trigger",
        renderChart(labels, triggerSeries(t), true, true, v -> Long.toString(Math.round(v))));
    addChart(
        doc,
        "Duration histogram",
        renderChart(
            t.histogram().stream().map(HistogramBucket::label).toList(),
            List.of(new Series("Builds", histogramValues(t.histogram()), SERIES[2])),
            false,
            true,
            v -> Long.toString(Math.round(v))));
  }

  private static double[] values(List<TrendPoint> buckets, ToDoubleFunction<TrendPoint> pick) {
    double[] result = new double[buckets.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = pick.applyAsDouble(buckets.get(i));
    }
    return result;
  }

  private static double[] histogramValues(List<HistogramBucket> buckets) {
    double[] result = new double[buckets.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = buckets.get(i).builds();
    }
    return result;
  }

  private static List<Series> triggerSeries(Trends t) {
    List<TrendPoint> base = t.buckets();
    List<Series> series = new java.util.ArrayList<>();
    for (var trigger : t.byTrigger()) {
      double[] values = new double[base.size()];
      for (int i = 0; i < base.size(); i++) {
        Instant at = base.get(i).bucket();
        values[i] =
            trigger.buckets().stream()
                .filter(point -> point.bucket().equals(at))
                .mapToDouble(TrendPoint::builds)
                .findFirst()
                .orElse(0);
      }
      series.add(
          new Series(
              triggerLabel(trigger.trigger()), values, SERIES[series.size() % SERIES.length]));
    }
    return series;
  }

  private static String triggerLabel(String trigger) {
    return switch (trigger) {
      case "POLL" -> "Poll";
      case "PULL_REQUEST" -> "Pull request";
      default -> "Manual";
    };
  }

  private static void addChart(Document doc, String title, BufferedImage image) {
    try {
      ByteArrayOutputStream png = new ByteArrayOutputStream();
      ImageIO.write(image, "png", png);
      Image picture = Image.getInstance(png.toByteArray());
      picture.scaleToFit(760, 240);
      // Keep the chart and its title on the same page; a bare image would
      // otherwise be pushed over a page break and leave the title orphaned.
      PdfPTable block = new PdfPTable(1);
      block.setWidthPercentage(100);
      block.setKeepTogether(true);
      block.setSpacingBefore(14);
      block.setSpacingAfter(4);
      PdfPCell label = new PdfPCell(new Phrase(title, HEADING));
      label.setBorder(0);
      label.setPadding(0);
      label.setPaddingBottom(5);
      block.addCell(label);
      PdfPCell chart = new PdfPCell(picture, false);
      chart.setBorder(0);
      chart.setPadding(0);
      chart.setHorizontalAlignment(Element.ALIGN_CENTER);
      block.addCell(chart);
      doc.add(block);
    } catch (Exception e) {
      throw new RuntimeException("Failed to render chart: " + title, e);
    }
  }

  private static BufferedImage renderChart(
      List<String> labels,
      List<Series> series,
      boolean stacked,
      boolean bars,
      DoubleFunction<String> yFormat) {
    BufferedImage image = new BufferedImage(CHART_W, CHART_H, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, CHART_W, CHART_H);
    if (labels.isEmpty()) {
      g.dispose();
      return image;
    }

    int left = 64;
    int right = 24;
    int top = 24;
    int bottom = 52;
    int plotW = CHART_W - left - right;
    int plotH = CHART_H - top - bottom;

    double rawMax = 0;
    if (stacked) {
      for (int i = 0; i < labels.size(); i++) {
        double total = 0;
        for (Series s : series) {
          total += s.values()[i];
        }
        rawMax = Math.max(rawMax, total);
      }
    } else {
      for (Series s : series) {
        for (double value : s.values()) {
          rawMax = Math.max(rawMax, value);
        }
      }
    }
    double max = rawMax > 0 ? rawMax : 1;

    java.awt.Font tickFont = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13);
    g.setFont(tickFont);
    var metrics = g.getFontMetrics();
    for (int i = 0; i <= 4; i++) {
      int y = top + plotH - (int) Math.round(plotH * i / 4.0);
      g.setColor(GRID);
      g.drawLine(left, y, left + plotW, y);
      String text = yFormat.apply(rawMax * i / 4.0);
      g.setColor(TICK);
      g.drawString(text, left - 8 - metrics.stringWidth(text), y + 4);
    }
    g.setColor(AXIS);
    g.setStroke(new BasicStroke(1f));
    g.drawLine(left, top, left, top + plotH);
    g.drawLine(left, top + plotH, left + plotW, top + plotH);

    double band = (double) plotW / labels.size();
    if (bars || stacked) {
      double groupW = band * 0.72;
      double barW = groupW / Math.max(1, series.size());
      for (int i = 0; i < labels.size(); i++) {
        double base = 0;
        for (int si = 0; si < series.size(); si++) {
          Series s = series.get(si);
          double height = s.values()[i] / max * plotH;
          int x;
          int y;
          int width;
          if (stacked) {
            x = (int) Math.round(left + band * i + (band - groupW) / 2);
            y = (int) Math.round(top + plotH - base - height);
            width = (int) Math.round(groupW);
            base += height;
          } else {
            x = (int) Math.round(left + band * i + (band - groupW) / 2 + si * barW + barW * 0.1);
            y = (int) Math.round(top + plotH - height);
            width = Math.max(1, (int) Math.round(barW * 0.8));
          }
          g.setColor(s.color());
          g.fillRect(x, y, width, Math.max(1, (int) Math.round(height)));
        }
      }
    } else {
      g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      for (Series s : series) {
        g.setColor(s.color());
        for (int i = 1; i < labels.size(); i++) {
          g.drawLine(
              lineX(left, plotW, labels.size(), i - 1),
              (int) Math.round(top + plotH - s.values()[i - 1] / max * plotH),
              lineX(left, plotW, labels.size(), i),
              (int) Math.round(top + plotH - s.values()[i] / max * plotH));
        }
        if (labels.size() <= 60) {
          for (int i = 0; i < labels.size(); i++) {
            int x = lineX(left, plotW, labels.size(), i);
            int y = (int) Math.round(top + plotH - s.values()[i] / max * plotH);
            g.fillOval(x - 3, y - 3, 6, 6);
          }
        }
      }
    }

    g.setFont(tickFont);
    g.setColor(TICK);
    int step = Math.max(1, (int) Math.ceil(labels.size() / 8.0));
    java.util.Set<Integer> shown = new java.util.TreeSet<>();
    for (int i = 0; i < labels.size(); i += step) {
      shown.add(i);
    }
    shown.add(labels.size() - 1);
    for (int i : shown) {
      String text = labels.get(i);
      int x =
          bars || stacked
              ? (int) Math.round(left + band * i + band / 2.0)
              : lineX(left, plotW, labels.size(), i);
      g.drawString(text, x - metrics.stringWidth(text) / 2, top + plotH + 22);
    }

    int legendX = left;
    int legendY = CHART_H - 14;
    for (Series s : series) {
      g.setColor(s.color());
      g.fillRect(legendX, legendY - 11, 12, 12);
      g.setColor(TICK);
      g.drawString(s.label(), legendX + 18, legendY);
      legendX += 18 + metrics.stringWidth(s.label()) + 26;
    }

    g.dispose();
    return image;
  }

  private static int lineX(int left, int plotW, int count, int index) {
    if (count <= 1) {
      return left + plotW / 2;
    }
    return (int) Math.round(left + (double) plotW * index / (count - 1));
  }

  private static String time(Instant at) {
    return at == null ? null : TIME.format(at);
  }
}

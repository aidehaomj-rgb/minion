package com.minion.gui.artifact;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** Read-only multi-sheet OOXML table preview. */
public final class WorkbookPreviewView extends BorderPane {
    private final TabPane tabs = new TabPane();

    public WorkbookPreviewView(List<XlsxPreview.Sheet> sheets) {
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (XlsxPreview.Sheet sheet : sheets) {
            TableView<ObservableList<String>> table = table(sheet);
            Label meta = new Label(sheet.truncated
                    ? "已截断为前 200 行、30 列" : "只读表格预览");
            meta.getStyleClass().add("artifact-meta");
            Button copy = new Button("复制 CSV");
            copy.setOnAction(e -> copy(sheet));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox bar = new HBox(8, meta, spacer, copy);
            bar.setAlignment(Pos.CENTER_LEFT);
            VBox.setVgrow(table, Priority.ALWAYS);
            VBox content = new VBox(7, bar, table);
            content.getStyleClass().add("workbook-sheet");
            Tab tab = new Tab(sheet.name, content);
            tabs.getTabs().add(tab);
        }
        setCenter(tabs);
    }

    private static TableView<ObservableList<String>> table(XlsxPreview.Sheet sheet) {
        TableView<ObservableList<String>> table = new TableView<ObservableList<String>>();
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        int columns = 0;
        for (List<String> row : sheet.rows) {
            columns = Math.max(columns, row.size());
            rows.add(FXCollections.observableArrayList(row));
        }
        columns = Math.min(XlsxPreview.MAX_COLUMNS, Math.max(1, columns));
        for (int column = 0; column < columns; column++) {
            final int index = column;
            TableColumn<ObservableList<String>, String> value =
                    new TableColumn<ObservableList<String>, String>(columnName(column));
            value.setCellValueFactory(data -> new ReadOnlyStringWrapper(
                    index < data.getValue().size() ? data.getValue().get(index) : ""));
            value.setPrefWidth(130);
            table.getColumns().add(value);
        }
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("此工作表为空"));
        return table;
    }

    private static void copy(XlsxPreview.Sheet sheet) {
        StringBuilder csv = new StringBuilder();
        for (List<String> row : sheet.rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) csv.append(',');
                csv.append(escape(row.get(i)));
            }
            csv.append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(csv.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        return text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return name.toString();
    }
}

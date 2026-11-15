package com.minion.gui.artifact;

import com.minion.gui.chat.BlockNodeFactory;
import com.minion.gui.chat.MarkdownRenderer;
import javafx.beans.binding.DoubleBinding;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.TextFlow;

import java.util.function.Consumer;
import java.util.function.Function;

/** Preview sizing belongs to the single outer viewport, not the preferred width of a document. */
public final class ArtifactDocumentNodes {
    private static final int READING_SIZE = 17;
    private ArtifactDocumentNodes() { }

    public static Node create(MarkdownRenderer.Block block, DoubleBinding width,
                              Consumer<String> open, Function<String, ContextMenu> menu) {
        if (block.type == MarkdownRenderer.Block.Type.TABLE) return table(block, width, open, menu);
        Node node;
        if (block.type == MarkdownRenderer.Block.Type.RULE) node = new Separator();
        else node = BlockNodeFactory.create(block, open, menu, READING_SIZE);
        if (node instanceof Region) {
            Region region=(Region)node;
            region.setMinWidth(0);
            region.prefWidthProperty().bind(width);
            region.maxWidthProperty().bind(width);
        }
        if (node instanceof TextFlow) {
            ((TextFlow)node).setLineSpacing(4);
            node.getStyleClass().add("artifact-prose");
        } else if (node instanceof Label) {
            ((Label)node).setWrapText(true);
            node.getStyleClass().add(block.type==MarkdownRenderer.Block.Type.CODE?"artifact-code":"artifact-heading");
        }
        return node;
    }

    private static Node table(MarkdownRenderer.Block table,DoubleBinding width,
                              Consumer<String> open,Function<String,ContextMenu> menu) {
        GridPane grid=new GridPane();
        grid.getStyleClass().add("artifact-table");
        int columns=table.rows.stream().mapToInt(r->r.cells.size()).max().orElse(1);
        // Scroll ONLY this table when there are too many columns, never the entire document.
        double minColumn=columns==1?150:104;
        double minGridWidth=Math.max(1,columns)*minColumn+2;
        grid.setMinWidth(minGridWidth);
        grid.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(width,minGridWidth));
        for(int column=0;column<columns;column++) {
            ColumnConstraints c=new ColumnConstraints();
            c.setMinWidth(minColumn);c.setPercentWidth(100.0/columns);grid.getColumnConstraints().add(c);
        }
        int rowIndex=0;
        for(MarkdownRenderer.TableRowData row:table.rows) {
            for(int column=0;column<row.cells.size();column++) {
                MarkdownRenderer.Block cell=new MarkdownRenderer.Block(MarkdownRenderer.Block.Type.PARAGRAPH);
                cell.text=row.cells.get(column);
                if(column<row.cellSpans.size())cell.spans=row.cellSpans.get(column);
                TextFlow text=(TextFlow)BlockNodeFactory.create(cell,open,menu,READING_SIZE);
                text.setMinWidth(0);
                text.getStyleClass().add(row.header?"artifact-table-head":rowIndex%2==0?"artifact-table-even":"artifact-table-odd");
                grid.add(text,column,rowIndex);
            }
            rowIndex++;
        }
        ScrollPane tableScroll=new ScrollPane(grid) {
            @Override protected double computePrefHeight(double w) {
                // ScrollPaneSkin's viewport preferred height can remain from the old width
                // when table cells reflow. Measure the grid at the same width it will use.
                return Math.ceil(grid.prefHeight(Math.max(minGridWidth,width.get())))+14;
            }
        };
        tableScroll.getStyleClass().add("artifact-table-scroll");
        tableScroll.setFitToWidth(false);
        tableScroll.setMinWidth(0);
        tableScroll.prefWidthProperty().bind(width);
        tableScroll.maxWidthProperty().bind(width);
        tableScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tableScroll.setFocusTraversable(false);
        // Table cells have dynamic wrapping height. Let the parent document own vertical scroll.
        return tableScroll;
    }
}

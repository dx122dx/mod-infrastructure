package com.billy65536.infrastructure.core.gui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * 表格流式构建器。
 *
 * <p>数据流：<pre>
 * new TableLayoutBuilder(tr, headers, columns)
 *     .addRow().text(...).position(...).item(...).done()
 *     .addRow()...done()
 *     .build();
 * </pre></p>
 */
public class TableLayoutBuilder {

	private final TextRenderer tr;
	private final String[] headers;
	private final TableLayout.ColumnSpec[] columnSpecs;
	private final int rowHeight;
	private final List<TableLayout.Row> rows = new ArrayList<>();
	private RowBuilder current;
	private int rowSeparatorColor;
	private int rowSeparatorHeight;

	public TableLayoutBuilder(TextRenderer tr, String[] headers, TableLayout.ColumnSpec[] columnSpecs) {
		this(tr, headers, columnSpecs, 20);
	}

	public TableLayoutBuilder(TextRenderer tr, String[] headers, TableLayout.ColumnSpec[] columnSpecs, int rowHeight) {
		this.tr = tr;
		this.headers = headers;
		this.columnSpecs = columnSpecs;
		this.rowHeight = rowHeight;
	}

	/** 行分隔线（color 为 0 时禁用）。 */
	public TableLayoutBuilder rowSeparator(int color, int height) {
		this.rowSeparatorColor = color;
		this.rowSeparatorHeight = height;
		return this;
	}

	/** 开始新行（若上一行未 done 则自动提交）。 */
	public RowBuilder addRow() {
		if (current != null) {
			current.commit();
		}
		current = new RowBuilder();
		return current;
	}

	/** 构建表格。 */
	public TableLayout build() {
		if (current != null) {
			current.commit();
			current = null;
		}
		int[] colWidths = computeColWidths();
		TableLayout layout = new TableLayout(tr, headers, columnSpecs, rowHeight, rows, colWidths);
		if (rowSeparatorColor != 0) {
			layout.setRowSeparator(rowSeparatorColor, rowSeparatorHeight);
		}
		return layout;
	}

	private int[] computeColWidths() {
		int[] widths = new int[headers.length];
		for (int c = 0; c < headers.length; c++) {
			widths[c] = tr.getWidth(headers[c]);
		}
		for (TableLayout.Row row : rows) {
			for (int c = 0; c < row.cells.size(); c++) {
				IContentCell cell = row.cells.get(c);
				if (cell != null) {
					widths[c] = Math.max(widths[c], cell.cellWidth(tr));
				}
			}
			if (!row.buttons.isEmpty() && widths.length > 0) {
				widths[widths.length - 1] = Math.max(widths[widths.length - 1],
						row.buttons.size() * 18 + (row.buttons.size() - 1) * 2 + 8);
			}
		}
		return widths;
	}

	/** 行构建器：逐列填充，{@code done()} 校验列数并提交。 */
	public final class RowBuilder {
		private final List<IContentCell> cells = new ArrayList<>();
		private final List<TableLayout.RowButton> buttons = new ArrayList<>();
		private TableLayout.EditableCell editable;
		private int editableCol = -1;
		private boolean committed;

		/** 空单元格占位。 */
		public RowBuilder blank() {
			cells.add(null);
			return this;
		}

		/** 文本单元格。 */
		public RowBuilder text(String string) {
			cells.add(TextCell.of(string));
			return this;
		}

		/** 文本单元格。 */
		public RowBuilder text(Text text) {
			cells.add(TextCell.of(text));
			return this;
		}

		/** 位置单元格（文本 + 可选点击回调）。 */
		public RowBuilder position(String display, Runnable onClick) {
			cells.add(PositionCell.of(display, onClick));
			return this;
		}

		/** 位置单元格（文本 + 可选点击回调）。 */
		public RowBuilder position(Text display, Runnable onClick) {
			cells.add(PositionCell.of(display, onClick));
			return this;
		}

		/** 物品图标单元格。 */
		public RowBuilder item(ItemStack stack) {
			cells.add(ItemCell.of(stack));
			return this;
		}

		/** 行按钮（渲染于操作列）。 */
		public RowBuilder button(Text label, Runnable action) {
			buttons.add(TableLayout.RowButton.of(label, action));
			return this;
		}

		/** 行按钮（自定义悬停颜色）。 */
		public RowBuilder button(Text label, Runnable action, int hoverColor) {
			buttons.add(new TableLayout.RowButton(label, action, hoverColor));
			return this;
		}

		/** 将最近一次填充的列标记为可编辑（点击进入编辑，提交时回调）。 */
		public RowBuilder editable(String value, Consumer<String> commit) {
			this.editable = new TableLayout.EditableCell(value, commit);
			this.editableCol = cells.size() - 1;
			return this;
		}

		/** 提交该行：校验列数后写入表格。 */
		public RowBuilder done() {
			if (committed) {
				throw new IllegalStateException("row already committed");
			}
			if (cells.size() != headers.length) {
				throw new IllegalStateException(
						"row cell count " + cells.size() + " != header count " + headers.length);
			}
			committed = true;
			return this;
		}

		private void commit() {
			if (committed) return;
			if (cells.size() != headers.length) {
				throw new IllegalStateException(
						"row cell count " + cells.size() + " != header count " + headers.length);
			}
			TableLayout.Row row = new TableLayout.Row(new ArrayList<>(cells));
			row.buttons.addAll(buttons);
			if (editable != null) {
				row.editable = editable;
				row.editableCol = editableCol;
			}
			rows.add(row);
			committed = true;
		}
	}
}

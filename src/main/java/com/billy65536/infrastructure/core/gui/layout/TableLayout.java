package com.billy65536.infrastructure.core.gui.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 表格布局（chunkscanner 表格能力 ∪ qab 列表能力的并集）。
 * 能力：表头（金色）/ 单元格（Text/Position/Item）/ 权重列 / 虚拟滚动 / 行按钮 / 编辑框 / tooltip / TSV 导出。
 */
public class TableLayout extends AbstractLayout {

	// ===== 常量 =====
	private static final int POSITION_COL_COLOR = 0xFF55FFFF;
	private static final int POSITION_HOVER_COLOR = 0xFFFFFF55;
	private static final int ITEM_ICON_SIZE = 16;
	private static final int COL_PADDING = 8;
	private static final int HEADER_HEIGHT = 16;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int BTN_WIDTH = 18;
	private static final int BTN_GAP = 2;
	private static final int BTN_HOVER_COLOR = 0xFFFFFFAA;
	private static final int HEADER_COLOR = 0xFFFFAA00;
	private static final int ROW_HOVER_BG = 0x18FFFFFF;

	// ===== 嵌套类型 =====

	/** 列规格：固定宽或权重宽 + 对齐/弹性/收缩优先级/下限宽度。 */
	public static final class ColumnSpec {
		public enum Align { LEFT, CENTER, RIGHT }
		private final boolean fixed;
		private final int size;
		private final Align align;
		private final boolean elastic;
		private final int shrinkPriority;
		private final int floorWidth;

		private ColumnSpec(boolean fixed, int size, Align align, boolean elastic, int shrinkPriority, int floorWidth) {
			this.fixed = fixed; this.size = size; this.align = align;
			this.elastic = elastic; this.shrinkPriority = shrinkPriority; this.floorWidth = floorWidth;
		}

		public static ColumnSpec ofFixed(int width) { return new ColumnSpec(true, width, Align.LEFT, false, 0, 0); }
		public static ColumnSpec ofFixed(int width, Align align) { return new ColumnSpec(true, width, align, false, 0, 0); }
		public static ColumnSpec ofWeight(int weight) { return new ColumnSpec(false, weight, Align.LEFT, false, 0, 0); }
		public static ColumnSpec ofWeight(int weight, Align align) { return new ColumnSpec(false, weight, align, false, 0, 0); }
		public ColumnSpec elastic() { return new ColumnSpec(fixed, size, align, true, shrinkPriority, floorWidth); }
		public ColumnSpec shrinkPriority(int priority) { return new ColumnSpec(fixed, size, align, elastic, priority, floorWidth); }
		public ColumnSpec floorWidth(int width) { return new ColumnSpec(fixed, size, align, elastic, shrinkPriority, width); }

		boolean isFixed() { return fixed; }
		int getSize() { return size; }
		Align getAlign() { return align; }
		boolean isElastic() { return elastic; }
		int getShrinkPriority() { return shrinkPriority; }
		int getFloorWidth() { return floorWidth; }
	}

	/** 行内按钮：渲染于操作列，点击触发回调。 */
	public record RowButton(Text label, Runnable action, int hoverColor) {
		public static RowButton of(Text label, Runnable action) {
			return new RowButton(label, action, BTN_HOVER_COLOR);
		}
	}

	/** 可编辑单元格：携带当前值与提交回调。 */
	public static final class EditableCell {
		private String value;
		private final Consumer<String> commit;
		EditableCell(String value, Consumer<String> commit) {
			this.value = value != null ? value : "";
			this.commit = commit;
		}
		public String getValue() { return value; }
		public void setValue(String value) { this.value = value != null ? value : ""; }
		void commit(String newValue) {
			this.value = newValue != null ? newValue : "";
			if (commit != null) commit.accept(this.value);
		}
	}

	/** 命中结果：按钮 / 可编辑单元格 / 所在列。 */
	public record CellHit(@Nullable RowButton button, @Nullable EditableCell editable, int col) {}

	/** 行数据结构（轻量，仅构建后读取，同一包内构建器可写）。 */
	static final class Row {
		final List<IContentCell> cells;
		final List<RowButton> buttons;
		@Nullable EditableCell editable;
		int editableCol = -1;
		Row(List<IContentCell> cells) {
			this.cells = cells;
			this.buttons = new ArrayList<>();
		}
	}

	// ===== 字段 =====
	private final TextRenderer tr;
	private final String[] headers;
	private final ColumnSpec[] columnSpecs;
	private final int rowHeight;
	private final List<Row> rows;
	private int rowSeparatorColor;
	private int rowSeparatorHeight;

	private int scrollOffset;
	private int[] columnX;
	private int[] columnWidth;
	private int contentWidth;

	private int hoveredRow = -1;
	private int hoveredCol = -1;
	private ItemStack hoveredItemStack;

	private int editRow = -1;
	private int editCol = -1;
	@Nullable private SpruceTextFieldWidget editor;
	@Nullable private SpruceWidgetLayout editorLayout;

	// ===== 构造 =====
	TableLayout(TextRenderer tr, String[] headers, ColumnSpec[] columnSpecs, int rowHeight,
			List<Row> rows, int[] colWidths) {
		this.tr = tr;
		this.headers = headers;
		this.columnSpecs = columnSpecs;
		this.rowHeight = rowHeight;
		this.rows = rows;
		this.columnX = new int[headers.length];
		this.columnWidth = Arrays.copyOf(colWidths, colWidths.length);
		this.contentWidth = colWidths.length * COL_PADDING;
		for (int w : colWidths) this.contentWidth += w;
	}

	void setRowSeparator(int color, int height) {
		this.rowSeparatorColor = color;
		this.rowSeparatorHeight = height;
	}

	// ===== 数据 API =====
	public int getRowCount() { return rows.size(); }
	public String[] getHeaders() { return headers; }

	@Nullable
	public IContentCell getCell(int row, int col) {
		if (row < 0 || row >= rows.size() || col < 0 || col >= headers.length) return null;
		return rows.get(row).cells.get(col);
	}

	@Nullable
	public String[] getRowAt(int rowIdx) {
		if (rowIdx < 0 || rowIdx >= rows.size()) return null;
		Row row = rows.get(rowIdx);
		String[] result = new String[row.cells.size()];
		for (int i = 0; i < row.cells.size(); i++) result[i] = cellToText(row.cells.get(i));
		return result;
	}

	public boolean isPositionColumn(int colIdx) {
		return colIdx >= 0 && colIdx < headers.length && !rows.isEmpty()
				&& rows.get(0).cells.get(colIdx) instanceof PositionCell;
	}

	@Nullable
	public List<Text> getCellTooltip(int rowIdx, int colIdx) {
		if (rowIdx < 0 || rowIdx >= rows.size() || colIdx < 0 || colIdx >= headers.length) return null;
		IContentCell cell = rows.get(rowIdx).cells.get(colIdx);
		if (cell instanceof TextCell rt && rt.tooltip() != null) return Arrays.asList(rt.tooltip());
		return null;
	}

	/** TSV 导出（表头 + 每行）。 */
	public void export(StringBuilder sb) {
		sb.append(String.join("\t", headers)).append("\n");
		for (Row row : rows) {
			for (int i = 0; i < row.cells.size(); i++) {
				if (i > 0) sb.append("\t");
				sb.append(cellToText(row.cells.get(i)));
			}
			sb.append("\n");
		}
	}

	// ===== 布局 / 滚动 API =====
	public int getHeaderHeight() { return HEADER_HEIGHT; }
	public int getRowHeight() { return rowHeight; }
	public int getContentWidth() { return contentWidth; }

	/** 按可用宽度重算列宽与列起点：固定列取设定宽，权重列按权重分配，富余给弹性列，超宽按收缩优先级收缩。 */
	public void reflow(int availWidth) {
		int n = columnSpecs.length;
		int totalWeight = 0;
		for (ColumnSpec spec : columnSpecs) {
			if (!spec.isFixed()) totalWeight += Math.max(1, spec.getSize());
		}
		int used = 0;
		for (int i = 0; i < n; i++) {
			ColumnSpec spec = columnSpecs[i];
			int w = spec.isFixed() ? spec.getSize()
					: (totalWeight > 0 ? (int) ((long) Math.max(0, availWidth - COL_PADDING * n) * spec.getSize() / totalWeight) : 0);
			columnWidth[i] = Math.max(w, spec.getFloorWidth());
			used += columnWidth[i];
		}
		int overflow = availWidth - used - COL_PADDING * n;
		if (overflow > 0) {
			int elasticCount = 0;
			for (ColumnSpec spec : columnSpecs) {
				if (!spec.isFixed() && spec.isElastic()) elasticCount++;
			}
			if (elasticCount > 0) {
				int per = overflow / elasticCount;
				for (int i = 0; i < n; i++) {
					if (!columnSpecs[i].isFixed() && columnSpecs[i].isElastic()) columnWidth[i] += per;
				}
			}
		}
		int shrink = used + COL_PADDING * n - availWidth;
		if (shrink > 0) {
			Integer[] order = new Integer[n];
			for (int i = 0; i < n; i++) order[i] = i;
			Arrays.sort(order, Comparator.comparingInt((Integer idx) -> columnSpecs[idx].getShrinkPriority()).reversed());
			for (int idx : order) {
				if (shrink <= 0) break;
				if (columnSpecs[idx].isFixed()) continue;
				int reduce = Math.min(columnWidth[idx] - columnSpecs[idx].getFloorWidth(), shrink);
				columnWidth[idx] -= reduce;
				shrink -= reduce;
			}
		}
		int x = 0;
		for (int i = 0; i < n; i++) {
			columnX[i] = x;
			x += columnWidth[i] + COL_PADDING;
		}
		contentWidth = x;
	}

	public int getColumnX(int col) { return col >= 0 && col < columnX.length ? columnX[col] : 0; }
	public int getColumnX(int col, int contentLeft) { return contentLeft + getColumnX(col); }
	public int getColumnWidth(int col) { return col >= 0 && col < columnWidth.length ? columnWidth[col] : 0; }

	/** 行的绝对 Y（含滚动偏移）。 */
	public int rowYAt(int row) { return getY() + HEADER_HEIGHT + row * rowHeight - scrollOffset; }
	public int getMaxScroll() { return Math.max(0, rows.size() * rowHeight - (height - HEADER_HEIGHT)); }
	public int getScrollOffset() { return scrollOffset; }
	public void setScrollOffset(int offset) { scrollOffset = Math.max(0, Math.min(offset, getMaxScroll())); }

	// ===== 悬停 =====
	public int getHoveredRow() { return hoveredRow; }
	public int getHoveredCol() { return hoveredCol; }
	@Nullable public ItemStack getHoveredItemStack() { return hoveredItemStack; }

	// ===== 编辑 =====
	public boolean isEditing() { return editor != null; }
	public int getEditRow() { return editRow; }
	public int getEditCol() { return editCol; }
	@Nullable public EditableCell getActiveEditor() {
		return editRow >= 0 && editRow < rows.size() ? rows.get(editRow).editable : null;
	}

	/** 在指定行/列启动编辑（该列需配置可编辑单元格）。 */
	public void startEdit(int row, int col) {
		if (row < 0 || row >= rows.size() || col < 0 || col >= headers.length) return;
		Row r = rows.get(row);
		if (r.editable == null || r.editableCol != col) return;
		if (editor != null) commitEdit();

		editRow = row;
		editCol = col;
		int ew = Math.max(20, columnWidth[col] - 4);
		int eh = Math.max(10, rowHeight - 6);
		SpruceTextFieldWidget field = new SpruceTextFieldWidget(Position.of(0, 0), ew, eh, Text.empty());
		field.setText(r.editable.getValue());
		field.setFocused(true);
		SpruceWidgetLayout layout = new SpruceWidgetLayout(field);
		this.editor = field;
		this.editorLayout = layout;
		this.addChild(layout);
		placeEditor();
	}

	public void commitEdit() {
		if (editor == null || editRow < 0 || editRow >= rows.size()) return;
		Row r = rows.get(editRow);
		if (r.editable != null) r.editable.commit(editor.getText());
		clearEditor();
	}

	public void cancelEdit() { clearEditor(); }

	private void clearEditor() {
		if (editorLayout != null && children != null) children.remove(editorLayout);
		editor = null;
		editorLayout = null;
		editRow = -1;
		editCol = -1;
	}

	private void placeEditor() {
		if (editorLayout == null) return;
		editorLayout.setBounds(
				getX() + columnX[editCol] + 2,
				rowYAt(editRow) + 2,
				Math.max(20, columnWidth[editCol] - 4),
				Math.max(10, rowHeight - 6));
	}

	// ===== 命中检测 =====
	@Nullable
	public CellHit hitTest(int row, int rowY, int contentLeft, double mouseX, double mouseY) {
		if (row < 0 || row >= rows.size()) return null;
		if (mouseY < rowY || mouseY >= rowY + rowHeight) return null;
		Row r = rows.get(row);
		int n = headers.length;
		if (!r.buttons.isEmpty() && n > 0) {
			int bx = contentLeft + columnX[n - 1] + COL_PADDING / 2;
			for (RowButton button : r.buttons) {
				if (mouseX >= bx && mouseX < bx + BTN_WIDTH) return new CellHit(button, null, n - 1);
				bx += BTN_WIDTH + BTN_GAP;
			}
		}
		if (r.editable != null && r.editableCol >= 0) {
			int ex = contentLeft + columnX[r.editableCol];
			if (mouseX >= ex && mouseX < ex + columnWidth[r.editableCol]) {
				return new CellHit(null, r.editable, r.editableCol);
			}
		}
		return null;
	}

	/** 鼠标 Y → 行索引（-1 表示表头或表格外）。 */
	public int getRowAtY(double mouseY) {
		double rel = mouseY - getY() - HEADER_HEIGHT + scrollOffset;
		if (rel < 0) return -1;
		int row = (int) (rel / rowHeight);
		return row < rows.size() ? row : -1;
	}

	// ===== 渲染 =====
	@Override
	protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
		if (editor != null) {
			int rowTop = rowYAt(editRow);
			if (rowTop + rowHeight < getY() + HEADER_HEIGHT || rowTop > getY() + height) {
				cancelEdit();
			} else {
				placeEditor();
			}
		}
		hoveredRow = -1;
		hoveredCol = -1;
		hoveredItemStack = null;

		int contentLeft = getX();
		int listTop = getY() + HEADER_HEIGHT;
		int rightEdge = getX() + getWidth();

		// 表头
		for (int c = 0; c < headers.length; c++) {
			int textW = tr.getWidth(headers[c]);
			int x = alignX(columnSpecs[c].getAlign(), contentLeft + columnX[c], columnWidth[c], textW);
			ctx.drawTextWithShadow(tr, Text.literal(headers[c]).formatted(Formatting.GOLD), x, getY(), HEADER_COLOR);
		}

		// 虚拟滚动：仅渲染可视行
		int firstRow = scrollOffset / rowHeight;
		int visibleRows = Math.max(0, (height - HEADER_HEIGHT) / rowHeight + 1);
		int lastRow = Math.min(rows.size(), firstRow + visibleRows);
		for (int r = firstRow; r < lastRow; r++) {
			int rowY = listTop + r * rowHeight - scrollOffset;
			boolean rowHovered = mouseY >= rowY && mouseY < rowY + rowHeight
					&& mouseY >= listTop && mouseY < getY() + height
					&& mouseX >= contentLeft && mouseX <= rightEdge;
			if (rowHovered) {
				hoveredRow = r;
				ctx.fill(contentLeft, rowY, rightEdge, rowY + rowHeight, ROW_HOVER_BG);
			}
			renderRow(ctx, r, rowY, contentLeft, mouseX, mouseY);
		}

		// 行分隔线
		if (rowSeparatorColor != 0 && rowSeparatorHeight > 0 && lastRow > firstRow) {
			for (int r = firstRow; r < lastRow; r++) {
				int rowY = listTop + r * rowHeight - scrollOffset;
				if (rowY > getY() + HEADER_HEIGHT) {
					ctx.fill(contentLeft, rowY - 1, rightEdge, rowY - 1 + rowSeparatorHeight, rowSeparatorColor);
				}
			}
		}

		// 滚动条
		int viewport = height - HEADER_HEIGHT;
		if (rows.size() * rowHeight > viewport) {
			int sx = rightEdge - SCROLLBAR_WIDTH;
			int thumbH = Math.max(20, viewport * viewport / (rows.size() * rowHeight));
			int maxScroll = getMaxScroll();
			int thumbY = maxScroll > 0
					? listTop + (viewport - thumbH) * scrollOffset / maxScroll
					: listTop;
			ctx.fill(sx, listTop, rightEdge, getY() + height, 0x66000000);
			ctx.fill(sx, thumbY, rightEdge, thumbY + thumbH, 0x99AAAAAA);
		}
	}

	private void renderRow(DrawContext ctx, int r, int rowY, int contentLeft, int mouseX, int mouseY) {
		Row row = rows.get(r);
		int n = headers.length;
		boolean hovered = r == hoveredRow;
		for (int c = 0; c < n; c++) {
			IContentCell cell = row.cells.get(c);
			if (cell == null) continue;
			int colLeft = contentLeft + columnX[c];
			int colW = columnWidth[c];
			boolean colHovered = hovered && mouseX >= colLeft && mouseX < colLeft + colW;
			int y = rowY + (rowHeight - 8) / 2;
			if (cell instanceof PositionCell pc) {
				int color = colHovered && pc.onClick() != null ? POSITION_HOVER_COLOR : POSITION_COL_COLOR;
				ctx.drawTextWithShadow(tr, pc.display(),
						alignX(columnSpecs[c].getAlign(), colLeft + COL_PADDING / 2, colW - COL_PADDING, tr.getWidth(pc.display())),
						y, color);
				if (colHovered) hoveredCol = c;
			} else if (cell instanceof ItemCell ic) {
				int iconY = rowY + (rowHeight - ITEM_ICON_SIZE) / 2;
				ctx.drawItem(ic.stack(), colLeft + COL_PADDING / 2, iconY);
				if (colHovered) {
					hoveredCol = c;
					hoveredItemStack = ic.stack();
				}
			} else if (cell instanceof TextCell tc) {
				ctx.drawTextWithShadow(tr, tc.text(),
						alignX(columnSpecs[c].getAlign(), colLeft + COL_PADDING / 2, colW - COL_PADDING, tr.getWidth(tc.text())),
						y, tc.color());
				if (colHovered) hoveredCol = c;
			} else if (cell instanceof MultiLineTextCell mlc) {
				List<String> lines = mlc.displayLines();
				int totalH = mlc.totalHeight();
				int startY = rowY + (rowHeight - totalH) / 2;
				int lineNo = 0;
				for (String line : lines) {
					ctx.getMatrices().push();
					ctx.getMatrices().translate(colLeft + COL_PADDING / 2, startY + lineNo * mlc.lineHeight(), 0);
					ctx.getMatrices().scale(mlc.scale(), mlc.scale(), 1);
					ctx.drawTextWithShadow(tr, Text.literal(line), 0, 0, mlc.color());
					ctx.getMatrices().pop();
					lineNo++;
				}
				if (colHovered) hoveredCol = c;
			} else if (cell instanceof DynamicTextCell dtc) {
				String cur = dtc.current();
				ctx.drawTextWithShadow(tr, Text.literal(cur),
						alignX(dtc.align(), colLeft + COL_PADDING / 2, colW - COL_PADDING, tr.getWidth(cur)),
						y, dtc.color());
				if (colHovered) hoveredCol = c;
			}
		}
		int bx = contentLeft + columnX[n - 1] + COL_PADDING / 2;
		for (RowButton button : row.buttons) {
			boolean btnHovered = hovered && mouseX >= bx && mouseX < bx + BTN_WIDTH;
			int color = btnHovered ? button.hoverColor() : 0xFFAAAAAA;
			ctx.drawTextWithShadow(tr, button.label(), bx, rowY + (rowHeight - 8) / 2, color);
			bx += BTN_WIDTH + BTN_GAP;
		}
	}

	private int alignX(ColumnSpec.Align align, int colLeft, int colWidth, int textWidth) {
		return switch (align) {
			case CENTER -> colLeft + (colWidth - textWidth) / 2;
			case RIGHT -> colLeft + colWidth - textWidth;
			default -> colLeft;
		};
	}

	private static String cellToText(IContentCell cell) {
		if (cell == null) return "";
		if (cell instanceof TextCell tc) return tc.text().getString();
		if (cell instanceof PositionCell pc) return pc.display().getString();
		if (cell instanceof ItemCell ic) return ic.stack().getName().getString();
		if (cell instanceof MultiLineTextCell mlc) {
			List<String> lines = mlc.displayLines();
			return lines.isEmpty() ? "" : lines.get(0);
		}
		if (cell instanceof DynamicTextCell dtc) return dtc.current();
		return "";
	}

	// ===== 事件 =====
	@Override
	protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		if (mouseX < getX() || mouseX > getX() + getWidth()
				|| mouseY < getY() || mouseY > getY() + height) {
			if (editor != null) commitEdit();
			return false;
		}
		if (editor != null) {
			// 点击编辑框外 → 提交并转入命中处理
			commitEdit();
		}
		int row = getRowAtY(mouseY);
		if (row < 0) return false;
		CellHit hit = hitTest(row, rowYAt(row), getX(), mouseX, mouseY);
		if (hit == null) return false;
		if (hit.button() != null) {
			hit.button().action().run();
		} else if (hit.editable() != null) {
			startEdit(row, hit.col());
		}
		return true;
	}

	@Override
	protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < getX() || mouseX > getX() + getWidth()
				|| mouseY < getY() || mouseY > getY() + height) {
			return false;
		}
		setScrollOffset(scrollOffset - (int) (amount * rowHeight * 2));
		return true;
	}

	@Override
	protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
		if (editor != null) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitEdit();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				cancelEdit();
				return true;
			}
			return false; // 其余按键由子节点（编辑框）处理
		}
		return false;
	}
}

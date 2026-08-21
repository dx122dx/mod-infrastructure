package com.billy65536.infrastructure.core.gui.layout;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 表格布局（chunkscanner 表格能力 ∪ qab 列表能力的并集）。
 * 能力：表头（金色）/ 单元格（Text/Position/Item/多行/动态/Button）/ 权重列 / 自然宽度 reflow /
 * 虚拟滚动 / 纵向滚动条 / 横向滚动条（Shift+滚轮与 thumb 拖动）/ 编辑框（一次点击切换编辑目标）/
 * 拖拽排序（手柄列拖动，拖出可视区删除）/ tooltip / TSV 导出 / PositionCell 左键/右键回调 /
 * ButtonCell 点击回调。
 */
public class TableLayout extends AbstractLayout {

	// ===== 常量 =====
	private static final int POSITION_COL_COLOR = 0xFF55FFFF;
	private static final int POSITION_HOVER_COLOR = 0xFFFFFF55;
	private static final int ITEM_ICON_SIZE = 16;
	private static final int COL_PADDING = 8;
	private static final int HEADER_HEIGHT = 16;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int HEADER_COLOR = 0xFFFFAA00;
	private static final int ROW_HOVER_BG = 0x18FFFFFF;
	/** 选中行高亮背景（金色半透明）。 */
	private static final int HIGHLIGHT_ROW_BG = 0x60FFAA00;
	/** 拖拽手柄字符。 */
	private static final String DRAG_HANDLE_TEXT = "\u280F";
	/** 拖拽中：距视口上/下边缘该距离内触发边缘自动滚动。 */
	private static final int DRAG_EDGE_SCROLL_THRESHOLD = 20;
	/** 拖拽行高亮背景。 */
	private static final int DRAG_ROW_BG = 0x30FFFFFF;
	/** 拖拽插入指示线颜色。 */
	private static final int DRAG_INSERT_LINE = 0xFFFFAA00;
	/** 拖出删除提示背景。 */
	private static final int DRAG_DELETE_BG = 0x50FF3333;

	// ===== 嵌套类型 =====

	/** 列规格：固定宽或权重宽 + 对齐/弹性/下限宽度。 */
	public static final class ColumnSpec {
		public enum Align { LEFT, CENTER, RIGHT }
		private final boolean fixed;
		private final int size;
		private final Align align;
		private final boolean elastic;
		private final int floorWidth;

		private ColumnSpec(boolean fixed, int size, Align align, boolean elastic, int floorWidth) {
			this.fixed = fixed; this.size = size; this.align = align;
			this.elastic = elastic; this.floorWidth = floorWidth;
		}

		public static ColumnSpec ofFixed(int width) { return new ColumnSpec(true, width, Align.LEFT, false, 0); }
		public static ColumnSpec ofFixed(int width, Align align) { return new ColumnSpec(true, width, align, false, 0); }
		public static ColumnSpec ofWeight(int weight) { return new ColumnSpec(false, weight, Align.LEFT, false, 0); }
		public static ColumnSpec ofWeight(int weight, Align align) { return new ColumnSpec(false, weight, align, false, 0); }
		public ColumnSpec elastic() { return new ColumnSpec(fixed, size, align, true, floorWidth); }
		public ColumnSpec floorWidth(int width) { return new ColumnSpec(fixed, size, align, elastic, width); }

		boolean isFixed() { return fixed; }
		int getSize() { return size; }
		Align getAlign() { return align; }
		boolean isElastic() { return elastic; }
		int getFloorWidth() { return floorWidth; }
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

	/** 拖拽排序回调：行移动。from 为原索引，to 为移动后目标索引。 */
	@FunctionalInterface
	public interface RowMoveCallback {
		void onRowMove(int from, int to);
	}

	/** 拖拽排序回调：行删除（拖出列表释放触发）。 */
	@FunctionalInterface
	public interface RowDeleteCallback {
		void onRowDelete(int index);
	}

	/** 命中结果：可编辑单元格 / 所在列。 */
	public record CellHit(@Nullable EditableCell editable, int col) {}

	/** 行数据结构（轻量，仅构建后读取，同一包内构建器可写）。 */
	static final class Row {
		final List<IContentCell> cells;
		@Nullable EditableCell editable;
		int editableCol = -1;
		Row(List<IContentCell> cells) {
			this.cells = cells;
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
	private int hScrollOffset;
	/** 横向滚动条 thumb 拖拽中。 */
	private boolean draggingHScroll;
	private int[] columnX;
	/** 构建期内容测量宽（max(表头, 单元格)），作为 reflow 的自适应基准。 */
	private final int[] naturalWidth;
	private int[] columnWidth;
	private int contentWidth;

	private int hoveredRow = -1;
	private int hoveredCol = -1;
	private ItemStack hoveredItemStack;

	/** 选中行索引（-1 表示无），仅作背景高亮提示，不影响点击/编辑逻辑。 */
	private int highlightedRow = -1;

	private int editRow = -1;
	private int editCol = -1;
	@Nullable private SpruceTextFieldWidget editor;
	@Nullable private SpruceWidgetLayout editorLayout;

	// 拖拽排序状态（dragHandleColumn < 0 表示未启用）
	private int dragHandleColumn = -1;
	@Nullable private RowMoveCallback rowMoveCallback;
	@Nullable private RowDeleteCallback rowDeleteCallback;
	private boolean draggingRow;
	private int dragStartRow = -1;
	private int dragHoverRow = -1;
	private boolean dragOutOfList;

	// ===== 构造 =====
	TableLayout(TextRenderer tr, String[] headers, ColumnSpec[] columnSpecs, int rowHeight,
			List<Row> rows, int[] colWidths) {
		this.tr = tr;
		this.headers = headers;
		this.columnSpecs = columnSpecs;
		this.rowHeight = rowHeight;
		this.rows = rows;
		this.columnX = new int[headers.length];
		this.naturalWidth = Arrays.copyOf(colWidths, colWidths.length);
		this.columnWidth = Arrays.copyOf(colWidths, colWidths.length);
		this.contentWidth = colWidths.length * COL_PADDING;
		for (int w : colWidths) this.contentWidth += w;
	}

	void setRowSeparator(int color, int height) {
		this.rowSeparatorColor = color;
		this.rowSeparatorHeight = height;
	}

	/** 启用拖拽排序：指定手柄列索引，并注入移动/删除回调。 */
	void setDragHandle(int columnIndex, RowMoveCallback onMove, RowDeleteCallback onDelete) {
		this.dragHandleColumn = columnIndex;
		this.rowMoveCallback = onMove;
		this.rowDeleteCallback = onDelete;
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

	/** 按可用宽度重算列宽与列起点：固定列取设定宽，其余列以内容测量宽为基准（自然宽度模式），
	 *  富余空间按权重分给弹性列；不足时不再压缩（超出部分由横向滚动条接管，见 {@link #hasHScroll()}）。 */
	public void reflow(int availWidth) {
		int n = columnSpecs.length;
		int used = 0;
		for (int i = 0; i < n; i++) {
			ColumnSpec spec = columnSpecs[i];
			// 固定列取设定宽；其余列以构建期内容测量宽（max(表头, 单元格)）为自适应基准，
			// 不再按权重比例分配（参考 chunkscanner 实现）。
			int w = spec.isFixed() ? spec.getSize() : naturalWidth[i];
			columnWidth[i] = Math.max(w, spec.getFloorWidth());
			used += columnWidth[i];
		}
		int overflow = availWidth - used - COL_PADDING * n;
		if (overflow > 0) {
			// 富余空间按权重分给弹性列
			int totalWeight = 0;
			for (ColumnSpec spec : columnSpecs) {
				if (!spec.isFixed() && spec.isElastic()) totalWeight += Math.max(1, spec.getSize());
			}
			if (totalWeight > 0) {
				int distributed = 0;
				for (int i = 0; i < n; i++) {
					ColumnSpec spec = columnSpecs[i];
					if (spec.isFixed() || !spec.isElastic()) continue;
					int add = (int) ((long) overflow * Math.max(1, spec.getSize()) / totalWeight);
					columnWidth[i] += add;
					distributed += add;
				}
				// 整除误差归入第一个弹性列
				int rem = overflow - distributed;
				if (rem > 0) {
					for (int i = 0; i < n; i++) {
						if (!columnSpecs[i].isFixed() && columnSpecs[i].isElastic()) {
							columnWidth[i] += rem;
							break;
						}
					}
				}
			}
		}
		// 不做“不足压缩”：内容超宽时保持自然宽度，由横向滚动条接管
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

	/** 行的局部 Y（含滚动偏移）。 */
	public int rowYAt(int row) { return HEADER_HEIGHT + row * rowHeight - scrollOffset; }

	/** 可视视口高度（横向滚动条可见时扣除其高度）。 */
	private int viewportHeight() {
		return height - HEADER_HEIGHT - (hasHScroll() ? SCROLLBAR_WIDTH : 0);
	}

	private boolean hasHScroll() { return contentWidth > getWidth(); }

	/** 最大纵向滚动偏移（向下对齐到整行，避免非整数倍偏移导致行绘制到表头区域）。 */
	public int getMaxScroll() {
		int max = Math.max(0, rows.size() * rowHeight - viewportHeight());
		return (max / rowHeight) * rowHeight;
	}
	public int getScrollOffset() { return scrollOffset; }
	public void setScrollOffset(int offset) { scrollOffset = Math.max(0, Math.min(offset, getMaxScroll())); }

	/**
	 * 滚动使指定行进入视口（越界索引忽略）。
	 * 行在视口上方则滚至行顶对齐表头下缘；在视口下方则滚至行底对齐视口底部。
	 */
	public void scrollToRow(int row) {
		if (row < 0 || row >= rows.size()) {
			return;
		}
		int rowTop = HEADER_HEIGHT + row * rowHeight - scrollOffset;
		int viewH = viewportHeight();
		if (rowTop < HEADER_HEIGHT) {
			setScrollOffset(scrollOffset - (HEADER_HEIGHT - rowTop));
		} else if (rowTop + rowHeight > viewH + HEADER_HEIGHT) {
			setScrollOffset(scrollOffset + (rowTop + rowHeight - viewH - HEADER_HEIGHT));
		}
	}

	/** 横向滚动范围与偏移（内容宽超过可视宽时出现横向滚动条）。 */
	public int getMaxHScroll() { return Math.max(0, contentWidth - getWidth()); }
	public int getHScrollOffset() { return hScrollOffset; }
	public void setHScrollOffset(int offset) { hScrollOffset = Math.max(0, Math.min(offset, getMaxHScroll())); }

	// ===== 高亮 =====
	/** 当前选中行索引（-1 表示无）。 */
	public int getHighlightedRow() { return highlightedRow; }

	/** 设置选中行索引（-1 清除）。范围越界自动收敛到有效行。 */
	public void setHighlightedRow(int row) {
		if (rows.isEmpty()) {
			highlightedRow = -1;
		} else {
			highlightedRow = row >= rows.size() ? rows.size() - 1 : Math.max(-1, row);
		}
	}

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

	/** 在指定行/列启动编辑（该列需配置可编辑单元格）。
	 *  <p>已处于编辑态时：目标为同一格则保持现状；目标为其他可编辑格则静默写回旧格值
	 *  （不触发 commit 回调、不重建表格），直接打开新格编辑框，实现一次点击切换编辑目标。</p> */
	public void startEdit(int row, int col) {
		if (row < 0 || row >= rows.size() || col < 0 || col >= headers.length) return;
		Row r = rows.get(row);
		if (r.editable == null || r.editableCol != col) return;
		if (editor != null) {
			// 同一格：保持编辑态
			if (editRow == row && editCol == col) return;
			// 切换目标：静默写回旧格值（不触发 commit 回调，避免业务重建打断编辑）
			Row old = rows.get(editRow);
			if (old.editable != null) old.editable.setValue(editor.getText());
			clearEditor();
		}

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
				columnX[editCol] - hScrollOffset + 2,
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
		int contentLeftX = contentLeft - hScrollOffset;
		if (r.editable != null && r.editableCol >= 0) {
			int ex = contentLeftX + columnX[r.editableCol];
			if (mouseX >= ex && mouseX < ex + columnWidth[r.editableCol]) {
				return new CellHit(r.editable, r.editableCol);
			}
		}
		return null;
	}

	/** 命中拖拽手柄列区域（局部坐标）。 */
	private boolean hitDragHandle(double mouseX, double mouseY) {
		if (dragHandleColumn < 0) return false;
		int contentLeftX = -hScrollOffset;
		int hx = contentLeftX + columnX[dragHandleColumn];
		int hw = columnWidth[dragHandleColumn];
		return mouseX >= hx && mouseX < hx + hw;
	}

	/** 鼠标 X（局部坐标）→ 列索引；未命中任何列返回 -1。 */
	public int getColumnAtX(double mouseX) {
		int contentLeftX = -hScrollOffset;
		for (int c = 0; c < headers.length; c++) {
			int cx = contentLeftX + columnX[c];
			if (mouseX >= cx && mouseX < cx + columnWidth[c]) return c;
		}
		return -1;
	}

	/** 鼠标 Y → 行索引（-1 表示表头或表格外）。 */
	public int getRowAtY(double mouseY) {
		double rel = mouseY - HEADER_HEIGHT + scrollOffset;
		if (rel < 0) return -1;
		int row = (int) (rel / rowHeight);
		return row < rows.size() ? row : -1;
	}

	// ===== 渲染 =====
	@Override
	protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
		if (editor != null) {
			int rowTop = rowYAt(editRow);
			if (rowTop + rowHeight < HEADER_HEIGHT || rowTop > height) {
				cancelEdit();
			} else {
				placeEditor();
			}
		}
		hoveredRow = -1;
		hoveredCol = -1;
		hoveredItemStack = null;

		int contentLeft = -hScrollOffset;
		int listTop = HEADER_HEIGHT;
		int rightEdge = getWidth();
		int viewport = viewportHeight();
		int contentBottom = height - (hasHScroll() ? SCROLLBAR_WIDTH : 0);

		// 裁剪到表格可视区：DrawContext.enableScissor(x1,y1,x2,y2) 参数均为 GUI 逻辑坐标
		// （内部 new ScreenRect(x1,y1,x2-x1,y2-y1)，自动乘 scale 并做 y 翻转），absX/absY 为
		// 屏幕绝对逻辑坐标；x2/y2 是右下角坐标而非宽高，必须传 absX+width / absY+height。
		// 切勿传物理像素（会被二次乘 scale 且 y 翻转错位）；也切勿把 width/height 当作 x2/y2
		// ——后者会让裁剪区底部提前 height 行，把横向滚动条与列表底部内容裁掉（qab 表格
		// absY=56 时列表下方出现约 56px 空白、横向滚动条消失，正是四问题反复的根因）。
		ctx.enableScissor(absX, absY, absX + width, absY + height);

		// 表头（横向滚动同步偏移）
		for (int c = 0; c < headers.length; c++) {
			int textW = tr.getWidth(headers[c]);
			int x = alignX(columnSpecs[c].getAlign(), contentLeft + columnX[c], columnWidth[c], textW);
			ctx.drawTextWithShadow(tr, Text.literal(headers[c]).formatted(Formatting.GOLD), x, 0, HEADER_COLOR);
		}

		// 虚拟滚动：仅渲染可视行
		int firstRow = scrollOffset / rowHeight;
		int visibleRows = Math.max(0, viewport / rowHeight + 1);
		int lastRow = Math.min(rows.size(), firstRow + visibleRows);
		for (int r = firstRow; r < lastRow; r++) {
			int rowY = listTop + r * rowHeight - scrollOffset;
			boolean rowHovered = mouseY >= rowY && mouseY < rowY + rowHeight
					&& mouseY >= listTop && mouseY < contentBottom
					&& mouseX >= 0 && mouseX <= rightEdge;
			if (r == highlightedRow) {
				ctx.fill(0, rowY, rightEdge, rowY + rowHeight, HIGHLIGHT_ROW_BG);
			}
			if (rowHovered) {
				hoveredRow = r;
				ctx.fill(0, rowY, rightEdge, rowY + rowHeight, ROW_HOVER_BG);
			}
			renderRow(ctx, r, rowY, contentLeft, mouseX, mouseY);
		}

		// 行分隔线
		if (rowSeparatorColor != 0 && rowSeparatorHeight > 0 && lastRow > firstRow) {
			for (int r = firstRow; r < lastRow; r++) {
				int rowY = listTop + r * rowHeight - scrollOffset;
				if (rowY > HEADER_HEIGHT) {
					ctx.fill(0, rowY - 1, rightEdge, rowY - 1 + rowSeparatorHeight, rowSeparatorColor);
				}
			}
		}

		// 拖拽排序反馈：被拖行高亮 + 插入指示线 / 拖出删除提示
		if (draggingRow && dragStartRow >= 0) {
			int startRowY = listTop + dragStartRow * rowHeight - scrollOffset;
			if (startRowY >= listTop && startRowY < contentBottom) {
				ctx.fill(0, startRowY, rightEdge, startRowY + rowHeight, DRAG_ROW_BG);
			}
			if (dragOutOfList) {
				// 拖出列表：整行红色提示
				ctx.fill(0, Math.max(listTop, startRowY), rightEdge,
						Math.min(contentBottom, startRowY + rowHeight), DRAG_DELETE_BG);
			} else if (dragHoverRow >= 0) {
				// 插入指示线：目标行上边缘
				int insertY = listTop + Math.min(dragHoverRow, rows.size()) * rowHeight - scrollOffset;
				if (insertY >= listTop && insertY <= contentBottom) {
					ctx.fill(0, insertY - 1, rightEdge, insertY + 1, DRAG_INSERT_LINE);
				}
			}
		}

		// 垂直滚动条
		if (rows.size() * rowHeight > viewport) {
			int sx = rightEdge - SCROLLBAR_WIDTH;
			int thumbH = Math.max(20, viewport * viewport / (rows.size() * rowHeight));
			int maxScroll = getMaxScroll();
			int thumbY = maxScroll > 0
					? listTop + (viewport - thumbH) * scrollOffset / maxScroll
					: listTop;
			ctx.fill(sx, listTop, rightEdge, contentBottom, 0x66000000);
			ctx.fill(sx, thumbY, rightEdge, thumbY + thumbH, 0x99AAAAAA);
		}

		// 横向滚动条
		if (hasHScroll()) {
			int maxHScroll = getMaxHScroll();
			int trackY = contentBottom;
			int trackH = SCROLLBAR_WIDTH;
			int thumbW = Math.max(20, getWidth() * getWidth() / contentWidth);
			int thumbX = maxHScroll > 0
					? (getWidth() - thumbW) * hScrollOffset / maxHScroll
					: 0;
			ctx.fill(0, trackY, rightEdge, trackY + trackH, 0x66000000);
			ctx.fill(thumbX, trackY, thumbX + thumbW, trackY + trackH, 0x99AAAAAA);
		}
		ctx.disableScissor();
	}

	private void renderRow(DrawContext ctx, int r, int rowY, int contentLeft, int mouseX, int mouseY) {
		Row row = rows.get(r);
		int n = headers.length;
		boolean hovered = r == hoveredRow;
		for (int c = 0; c < n; c++) {
			// 编辑中的单元格由编辑框接管，不绘制底层文本/内容
			if (r == editRow && c == editCol) continue;
			int colLeft = contentLeft + columnX[c];
			int colW = columnWidth[c];
			boolean colHovered = hovered && mouseX >= colLeft && mouseX < colLeft + colW;
			int y = rowY + (rowHeight - 8) / 2;
			// 拖拽手柄列：左侧绘制手柄字符（业务内容照常渲染）
			if (c == dragHandleColumn) {
				ctx.drawTextWithShadow(tr, Text.literal(DRAG_HANDLE_TEXT),
						colLeft + COL_PADDING / 2, y, 0xFFAAAAAA);
			}
			IContentCell cell = row.cells.get(c);
			if (cell == null) continue;
			// 可编辑列（非编辑中）：显示 editable 当前值（静默写回后立即反映，无需重建表格）
			if (row.editable != null && row.editableCol == c) {
				String cur = row.editable.getValue();
				int color = cell instanceof TextCell tc ? tc.color() : 0xFFFFFFFF;
				ctx.drawTextWithShadow(tr, Text.literal(cur),
						alignX(columnSpecs[c].getAlign(), colLeft + COL_PADDING / 2, colW - COL_PADDING, tr.getWidth(cur)),
						y, color);
				if (colHovered) hoveredCol = c;
				continue;
			}
			if (cell instanceof PositionCell pc) {
				int color = colHovered && (pc.onClick() != null || pc.onRightClick() != null)
						? POSITION_HOVER_COLOR : POSITION_COL_COLOR;
				ctx.drawTextWithShadow(tr, pc.display(),
						alignX(columnSpecs[c].getAlign(), colLeft + COL_PADDING / 2, colW - COL_PADDING, tr.getWidth(pc.display())),
						y, color);
				if (colHovered) hoveredCol = c;
			} else if (cell instanceof ButtonCell bc) {
				// 按钮单元格：普通/悬停双色文本
				int color = colHovered ? bc.hoverColor() : bc.color();
				ctx.drawTextWithShadow(tr, bc.display(),
						alignX(columnSpecs[c].getAlign(), colLeft + COL_PADDING / 2, colW - COL_PADDING, tr.getWidth(bc.display())),
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
		if (cell instanceof ButtonCell bc) return bc.display().getString();
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
		if (button != 0 && button != 1) return false;
		if (mouseX < 0 || mouseX > getWidth()
				|| mouseY < 0 || mouseY > height) {
			// 表格外点击：离开编辑态
			if (editor != null) commitEdit();
			return false;
		}
		if (button == 0) {
			// 横向滚动条区域：thumb 命中进入拖拽态；轨道空白处消费点击（不落入行处理）
			if (hasHScroll() && mouseY >= height - SCROLLBAR_WIDTH) {
				if (editor != null) commitEdit();
				if (hScrollThumbHit(mouseX, mouseY)) {
					draggingHScroll = true;
				}
				return true;
			}
			int row = getRowAtY(mouseY);
			if (row < 0) {
				// 表头：离开编辑态
				if (editor != null) commitEdit();
				return false;
			}
			// 拖拽手柄列：按下开始拖拽排序
			if (hitDragHandle(mouseX, mouseY)) {
				if (editor != null) commitEdit();
				draggingRow = true;
				dragStartRow = row;
				dragHoverRow = row;
				dragOutOfList = false;
				return true;
			}
			// ButtonCell / PositionCell 列：命中可点击格时执行回调并消费事件（按钮优先于行点击）
			int clickedCol = getColumnAtX(mouseX);
			if (clickedCol >= 0 && clickedCol < rows.get(row).cells.size()) {
				IContentCell cell = rows.get(row).cells.get(clickedCol);
				if (cell instanceof ButtonCell bc && bc.onClick() != null) {
					if (editor != null) commitEdit();
					bc.onClick().run();
					return true;
				}
				if (cell instanceof PositionCell pc && pc.onClick() != null) {
					if (editor != null) commitEdit();
					pc.onClick().run();
					return true;
				}
			}
			CellHit hit = hitTest(row, rowYAt(row), 0, mouseX, mouseY);
			if (hit == null) {
				// 行内空白：离开编辑态
				if (editor != null) commitEdit();
				return false;
			}
			if (hit.editable() != null) {
				// 可编辑格：一次点击直接切换（同格保持 / 异格静默切换），不触发 commit 回调
				startEdit(row, hit.col());
				return true;
			}
			return false;
		}
		// 右键：仅分派 PositionCell 右键回调（命中且非 null 才执行并消费）
		int row = getRowAtY(mouseY);
		if (row < 0) return false;
		int clickedCol = getColumnAtX(mouseX);
		if (clickedCol >= 0 && clickedCol < rows.get(row).cells.size()) {
			IContentCell cell = rows.get(row).cells.get(clickedCol);
			if (cell instanceof PositionCell pc && pc.onRightClick() != null) {
				if (editor != null) commitEdit();
				pc.onRightClick().run();
				return true;
			}
		}
		return false;
	}

	/** 横向滚动条 thumb 命中检测（局部坐标）。 */
	private boolean hScrollThumbHit(double mouseX, double mouseY) {
		if (!hasHScroll()) return false;
		int trackY = height - SCROLLBAR_WIDTH;
		if (mouseY < trackY || mouseY > height) return false;
		int thumbW = Math.max(20, getWidth() * getWidth() / contentWidth);
		int maxHScroll = getMaxHScroll();
		int thumbX = maxHScroll > 0
				? (getWidth() - thumbW) * hScrollOffset / maxHScroll
				: 0;
		return mouseX >= thumbX && mouseX < thumbX + thumbW;
	}

	@Override
	protected boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (button != 0) return false;
		if (draggingHScroll) {
			// thumb 拖动：thumb 中心跟随鼠标 X，逆推滚动比例
			int thumbW = Math.max(20, getWidth() * getWidth() / contentWidth);
			int trackW = getWidth() - thumbW;
			double ratio = trackW > 0 ? (mouseX - thumbW / 2.0) / trackW : 0;
			setHScrollOffset((int) Math.round(ratio * getMaxHScroll()));
			return true;
		}
		if (draggingRow) {
			updateDragHover(mouseY);
			// 边缘自动滚动：鼠标靠近视口上/下边缘（阈值 20px）时逐行滚动
			if (mouseY < HEADER_HEIGHT + DRAG_EDGE_SCROLL_THRESHOLD) {
				setScrollOffset(scrollOffset - rowHeight);
			} else if (mouseY > height - SCROLLBAR_WIDTH - DRAG_EDGE_SCROLL_THRESHOLD) {
				setScrollOffset(scrollOffset + rowHeight);
			}
			return true;
		}
		return false;
	}

	/** 拖拽中：根据鼠标 Y 更新悬停目标行与"拖出列表"标志。 */
	private void updateDragHover(double mouseY) {
		int listBottom = height - (hasHScroll() ? SCROLLBAR_WIDTH : 0);
		// 拖出可视区（表头之上或列表可视区之下）→ 删除
		dragOutOfList = mouseY < HEADER_HEIGHT || mouseY > listBottom;
		if (dragOutOfList) {
			dragHoverRow = -1;
			return;
		}
		int row = getRowAtY(mouseY);
		if (row < 0) {
			dragHoverRow = rows.size();
			return;
		}
		int rowTop = HEADER_HEIGHT + row * rowHeight - scrollOffset;
		dragHoverRow = mouseY < rowTop + rowHeight / 2 ? row : row + 1;
	}

	@Override
	protected boolean onMouseReleased(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		if (draggingHScroll) {
			draggingHScroll = false;
			return true;
		}
		if (draggingRow) {
			updateDragHover(mouseY);
			boolean out = dragOutOfList;
			int from = dragStartRow;
			int to = dragHoverRow;
			draggingRow = false;
			dragStartRow = -1;
			dragHoverRow = -1;
			dragOutOfList = false;
			if (out) {
				if (rowDeleteCallback != null) rowDeleteCallback.onRowDelete(from);
			} else if (to != from && to != from + 1) {
				// to 为目标位置（插到该行之前）；相邻/原地不触发移动
				if (rowMoveCallback != null) rowMoveCallback.onRowMove(from, to);
			}
			return true;
		}
		return false;
	}

	/** 窗口失焦时兜底取消拖拽（防止 mouseReleased 丢失导致状态卡死）。 */
	@Override
	public void tick() {
		super.tick();
		if (draggingRow && !MinecraftClient.getInstance().isWindowFocused()) {
			draggingRow = false;
			dragStartRow = -1;
			dragHoverRow = -1;
			dragOutOfList = false;
		}
	}

	@Override
	protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < 0 || mouseX > getWidth()
				|| mouseY < 0 || mouseY > height) {
			return false;
		}
		if (Screen.hasShiftDown() && hasHScroll()) {
			setHScrollOffset(hScrollOffset - (int) (amount * 24));
		} else {
			// 偏移对齐到整行（getMaxScroll 已对齐），防止滚轮 amount 非整数时滚动偏移变为
			// 非 rowHeight 倍数，导致首行部分绘制到表头区域
			int delta = (int) Math.round(amount * rowHeight * 2);
			setScrollOffset((scrollOffset - delta) / rowHeight * rowHeight);
		}
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

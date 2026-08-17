package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常信息展示布局（chunkscanner ErrorDisplayLayout 的新框架控件化）。
 *
 * <p>展示五部分：基础描述信息 / 堆栈轨迹 / 异常链 / 被抑制异常 / 诊断元数据（栈深度、serialVersionUID）。
 * 支持垂直滚动；每行渲染为带颜色的单行文本。不包含表格模式功能。</p>
 */
public class ErrorDisplayLayout extends AbstractLayout {

	private static final int ROW_HEIGHT = 20;
	private static final int LEFT_PADDING = 8;

	private static final int COLOR_WHITE = 0xFFFFFFFF;
	private static final int COLOR_RED = 0xFFFF5555;
	private static final int COLOR_YELLOW = 0xFFFFFF55;
	private static final int COLOR_GOLD = 0xFFFFAA00;
	private static final int COLOR_GRAY = 0xFFAAAAAA;

	private final TextRenderer textRenderer;
	private final List<ErrorRow> rows;
	private final int contentWidth;
	private int scrollOffset;

	/** 构建异常信息展示布局。 */
	public ErrorDisplayLayout(TextRenderer textRenderer, Throwable exception) {
		this.textRenderer = textRenderer;
		this.rows = new ArrayList<>();
		buildRows(exception);
		int maxW = 0;
		for (ErrorRow row : rows) {
			maxW = Math.max(maxW, textRenderer.getWidth(row.text));
		}
		this.contentWidth = maxW + LEFT_PADDING;
	}

	// ==================== 构建数据行 ====================

	private void buildRows(Throwable e) {
		addRow("====================", COLOR_GOLD);
		addRow("=== Error Details ===", COLOR_GOLD);
		addRow("====================", COLOR_GOLD);
		addBlank();
		addRow("Error Message:", COLOR_RED);
		addBlank();

		// ---- Section 1: 基础描述信息 ----
		addSection("=== Exception Details ===");
		addLabeled("Type:", e.getClass().getName(), COLOR_RED, COLOR_RED);
		String message = e.getMessage();
		addLabeled("Message:", message != null ? message : "(null)", COLOR_RED, COLOR_RED);
		String localMsg = e.getLocalizedMessage();
		addLabeled("Localized:", localMsg != null ? localMsg : "(null)", COLOR_GRAY, COLOR_GRAY);
		addBlank();

		// ---- Section 2: 堆栈轨迹 ----
		addSection("--- Stack Trace ---");
		StackTraceElement[] stackTrace = e.getStackTrace();
		if (stackTrace.length == 0) {
			addRow("  (stack trace unavailable - possibly optimized out by -XX:+OmitStackTraceInFastThrow)", COLOR_GRAY);
		} else {
			for (StackTraceElement ste : stackTrace) {
				addRow("  " + formatFrame(ste), COLOR_WHITE);
			}
		}

		// ---- Section 3: 异常链 (Cause) ----
		Throwable cause = e.getCause();
		if (cause != null) {
			addBlank();
			addSection("--- Caused by ---");
			renderCauseChain(cause, 0);
		}

		// ---- Section 4: 被抑制的异常 (Suppressed) ----
		Throwable[] suppressed = e.getSuppressed();
		if (suppressed.length > 0) {
			addBlank();
			addSection("--- Suppressed Exceptions (" + suppressed.length + ") ---");
			for (int i = 0; i < suppressed.length; i++) {
				Throwable sup = suppressed[i];
				addRow("  [" + i + "] " + sup.getClass().getName()
						+ ": " + (sup.getMessage() != null ? sup.getMessage() : "(null)"), COLOR_YELLOW);
				for (StackTraceElement ste : sup.getStackTrace()) {
					addRow("      " + formatFrame(ste), COLOR_GRAY);
				}
				addBlank();
			}
		}

		// ---- Section 5: 诊断与序列化元数据 ----
		addBlank();
		addSection("--- Diagnostics ---");
		addLabeled("Stack depth:", String.valueOf(stackTrace.length), COLOR_GRAY, COLOR_GRAY);
		addLabeled("serialVersionUID:", getSerialVersionUID(e), COLOR_GRAY, COLOR_GRAY);
	}

	/** 递归渲染异常链（Cause），嵌套深度递增。 */
	private void renderCauseChain(Throwable cause, int depth) {
		String indent = "  ".repeat(depth);
		int msgColor = (depth == 0) ? COLOR_YELLOW : COLOR_GRAY;
		addRow(indent + cause.getClass().getName()
				+ ": " + (cause.getMessage() != null ? cause.getMessage() : "(null)"), msgColor);
		for (StackTraceElement ste : cause.getStackTrace()) {
			addRow(indent + "  " + formatFrame(ste), COLOR_GRAY);
		}
		Throwable nested = cause.getCause();
		if (nested != null) {
			addRow(indent + "  Caused by:", COLOR_YELLOW);
			renderCauseChain(nested, depth + 1);
		}
	}

	// ==================== 工具方法 ====================

	/** 格式化堆栈帧为标准 Java 格式，如 {@code at com.example.Foo.bar(Foo.java:42)}。 */
	private static String formatFrame(StackTraceElement ste) {
		StringBuilder sb = new StringBuilder("at ");
		sb.append(ste.getClassName()).append('.').append(ste.getMethodName());
		if (ste.isNativeMethod()) {
			sb.append("(Native Method)");
		} else {
			String fileName = ste.getFileName();
			int lineNumber = ste.getLineNumber();
			if (fileName != null && lineNumber >= 0) {
				sb.append('(').append(fileName).append(':').append(lineNumber).append(')');
			} else if (fileName != null) {
				sb.append('(').append(fileName).append(')');
			} else {
				sb.append("(Unknown Source)");
			}
		}
		return sb.toString();
	}

	/** 通过 {@link ObjectStreamClass} 获取异常的 serialVersionUID。 */
	private static String getSerialVersionUID(Throwable e) {
		try {
			return ObjectStreamClass.lookup(e.getClass()).getSerialVersionUID() + "L";
		} catch (Exception ex) {
			return "(unavailable)";
		}
	}

	// ---- 行构建辅助 ----

	private void addSection(String string) { rows.add(ErrorRow.of(string, COLOR_GOLD)); }
	private void addRow(String string, int color) { rows.add(ErrorRow.of(string, color)); }
	private void addLabeled(String label, String value, int labelColor, int valueColor) {
		rows.add(ErrorRow.of("  " + label + " " + value, valueColor));
	}
	private void addBlank() { rows.add(ErrorRow.of("", COLOR_WHITE)); }

	// ==================== 控件型实现 ====================

	/** 内容总宽。 */
	public int getContentWidth() { return contentWidth; }

	public int getRowCount() { return rows.size(); }
	public int getMaxScroll() { return Math.max(0, rows.size() * ROW_HEIGHT - height); }
	public int getScrollOffset() { return scrollOffset; }
	public void setScrollOffset(int offset) { scrollOffset = Math.max(0, Math.min(offset, getMaxScroll())); }

	/** TSV 导出（每行文本）。 */
	public void export(StringBuilder sb) {
		for (ErrorRow row : rows) {
			sb.append(row.text.getString()).append('\n');
		}
	}

	@Override
	protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
		int firstRow = scrollOffset / ROW_HEIGHT;
		int visibleRows = Math.max(0, height / ROW_HEIGHT + 1);
		int lastRow = Math.min(rows.size(), firstRow + visibleRows);
		for (int i = firstRow; i < lastRow; i++) {
			ErrorRow row = rows.get(i);
			if (row.text.getString().isEmpty()) continue;
			int y = i * ROW_HEIGHT - scrollOffset;
			ctx.drawTextWithShadow(textRenderer, row.text, LEFT_PADDING, y, row.color);
		}
		// 滚动条（局部坐标）
		if (rows.size() * ROW_HEIGHT > height) {
			int sx = width - 6;
			int thumbH = Math.max(20, height * height / (rows.size() * ROW_HEIGHT));
			int maxScroll = getMaxScroll();
			int thumbY = maxScroll > 0 ? (height - thumbH) * scrollOffset / maxScroll : 0;
			ctx.fill(sx, 0, width, height, 0x66000000);
			ctx.fill(sx, thumbY, width, thumbY + thumbH, 0x99AAAAAA);
		}
	}

	@Override
	protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < 0 || mouseX > width
				|| mouseY < 0 || mouseY > height) {
			return false;
		}
		setScrollOffset(scrollOffset - (int) (amount * ROW_HEIGHT * 2));
		return true;
	}

	// ==================== 内部数据结构 ====================

	/** 单行错误展示条目。 */
	private record ErrorRow(Text text, int color) {
		static ErrorRow of(String string, int color) { return new ErrorRow(Text.literal(string), color); }
	}
}

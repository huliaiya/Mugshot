package main

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"
)

// dragWrap 让滚动内容在触屏等无滚轮设备上支持拖拽滚动。
// Fyne 的 Scroll 在桌面端忽略拖拽事件（仅移动端响应，见 internal/widget/scroller.go），
// 命中测试选中最深层的可拖拽对象，因此在 Scroll 内容外再包一层自定义 Widget，
// 拖拽事件就会先落到本层而不是被 Scroll 丢弃。
type dragWrap struct {
	widget.BaseWidget
	content fyne.CanvasObject
	scroll  *container.Scroll
}

// newDragScroll 创建支持拖拽滚动的 Scroll 容器。
func newDragScroll(content fyne.CanvasObject) *container.Scroll {
	w := &dragWrap{content: content}
	w.ExtendBaseWidget(w)
	s := container.NewScroll(w)
	w.scroll = s
	return s
}

func (w *dragWrap) CreateRenderer() fyne.WidgetRenderer {
	return widget.NewSimpleRenderer(w.content)
}

// Dragged 与 Scroll 移动端拖拽行为一致：拖动方向与内容移动方向相同，
// 边界内收敛，位置无变化时跳过重绘。
func (w *dragWrap) Dragged(e *fyne.DragEvent) {
	if w.scroll == nil {
		return
	}
	s := w.scroll
	size := s.Size()
	min := s.Content.MinSize()
	maxX := min.Width - size.Width
	if maxX < 0 {
		maxX = 0
	}
	maxY := min.Height - size.Height
	if maxY < 0 {
		maxY = 0
	}
	nx := clampF(s.Offset.X-e.Dragged.DX, maxX)
	ny := clampF(s.Offset.Y-e.Dragged.DY, maxY)
	if nx == s.Offset.X && ny == s.Offset.Y {
		return
	}
	s.Offset = fyne.NewPos(nx, ny)
	s.Refresh()
}

func (w *dragWrap) DragEnd() {}

func clampF(v, max float32) float32 {
	if v < 0 {
		return 0
	}
	if v > max {
		return max
	}
	return v
}

package io.fair_acc.chartfx.axes.spi;

import java.util.List;
import java.util.function.ToDoubleFunction;

import io.fair_acc.chartfx.ui.css.PathStyle;
import io.fair_acc.chartfx.ui.css.TextStyle;
import io.fair_acc.chartfx.utils.FXUtils;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.CacheHint;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.StringConverter;

import io.fair_acc.chartfx.axes.Axis;
import io.fair_acc.chartfx.axes.AxisLabelFormatter;
import io.fair_acc.chartfx.axes.AxisLabelOverlapPolicy;
import io.fair_acc.chartfx.axes.spi.format.DefaultFormatter;
import io.fair_acc.chartfx.axes.spi.format.DefaultTimeFormatter;
import io.fair_acc.chartfx.ui.ResizableCanvas;
import io.fair_acc.chartfx.ui.geometry.Side;
import io.fair_acc.dataset.event.AxisChangeEvent;

/**
 * @author rstein
 */
public abstract class AbstractAxis extends AbstractAxisParameter implements Axis {
    protected static final double MIN_NARROW_FONT_SCALE = 0.7;
    protected static final double MAX_NARROW_FONT_SCALE = 1.0;
    private final transient Canvas canvas = new ResizableCanvas();
    protected boolean labelOverlap;
    protected double scaleFont = 1.0;
    protected double maxLabelHeight;
    protected double maxLabelWidth;

    private final transient ObjectProperty<AxisLabelFormatter> axisFormatter = new SimpleObjectProperty<>(this,
            "axisLabelFormatter", null) {
        /**
         * default fall-back formatter in case no {@code axisFormatter} is specified (ie. 'null')
         */
        private final AxisLabelFormatter defaultFormatter = new DefaultFormatter(AbstractAxis.this);
        private final AxisLabelFormatter defaultTimeFormatter = new DefaultTimeFormatter(AbstractAxis.this);
        private final AxisLabelFormatter defaultLogTimeFormatter = new DefaultTimeFormatter(AbstractAxis.this);

        @Override
        public AxisLabelFormatter get() {
            final AxisLabelFormatter superImpl = super.get();
            if (superImpl != null) {
                return superImpl;
            }

            if (isTimeAxis()) {
                if (isLogAxis()) {
                    return defaultLogTimeFormatter;
                }
                return defaultTimeFormatter;
            }

            // non-time format
            return defaultFormatter;
        }

        @Override
        protected void invalidated() {
            forceRedraw();
        }
    };



    protected AbstractAxis() {
        super();
        setMouseTransparent(false);
        setPickOnBounds(true);
        canvas.setMouseTransparent(false);
        canvas.toFront();
        if (!canvas.isCache()) {
            canvas.setCache(true);
            canvas.setCacheHint(CacheHint.QUALITY);
        }
        getChildren().add(canvas);

        // TODO: what is this for? potentially removable? could we just disable padding on the Canvas?
        final ChangeListener<? super Number> axisSizeChangeListener = (c, o, n) -> {
            // N.B. add padding along axis to allow oversized labels
            final double padding = getAxisPadding();
            if (getSide().isHorizontal()) {
                canvas.resize(getWidth() + (2 * padding), getHeight());
                canvas.setLayoutX(-padding);
            } else {
                canvas.resize(getWidth(), getHeight() + (2 * padding));
                canvas.setLayoutY(-padding);
            }
            invalidate();
            requestLayout();
        };


        axisPaddingProperty().addListener((ch, o, n) -> {
            final double padding = getAxisPadding();
            if (getSide().isHorizontal()) {
                canvas.resize(getWidth() + (2 * padding), getHeight());
                canvas.setLayoutX(-padding);
            } else {
                canvas.resize(getWidth() + (2 * padding), getHeight() + (2 * padding)); // TODO: why is this different from the axisSizeChangeListener?
                canvas.setLayoutY(-padding);
            }
        });

        widthProperty().addListener(axisSizeChangeListener);
        heightProperty().addListener(axisSizeChangeListener);
        rotateProperty().addListener((observable, oldValue, value) -> {
            updateTickLabelAlignment();
        });

        // set default axis title/label alignment
        sideProperty().addListener((ch, o, n) -> {
            updateTickLabelAlignment();
            switch (n) {
            case CENTER_HOR:
            case CENTER_VER:
                getAxisLabel().setTextAlignment(TextAlignment.RIGHT);
                break;
            case TOP:
            case BOTTOM:
            case LEFT:
            case RIGHT:
            default:
                getAxisLabel().setTextAlignment(TextAlignment.CENTER);
                break;
            }
        });
        updateTickLabelAlignment();

        invertAxisProperty().addListener((ch, o, n) -> Platform.runLater(this::forceRedraw));

        VBox.setVgrow(this, Priority.ALWAYS);
        HBox.setHgrow(this, Priority.ALWAYS);
    }

    protected AbstractAxis(final double lowerBound, final double upperBound) {
        this();
        set(lowerBound, upperBound);
        setAutoRanging(false);
    }

    public ObjectProperty<AxisLabelFormatter> axisLabelFormatterProperty() {
        return axisFormatter;
    }

    /**
     * Computes the preferred tick unit based on the upper/lower bounds and the length of the axis in screen
     * coordinates.
     *
     * @param axisLength the length in screen coordinates
     * @return the tick unit
     */
    public abstract double computePreferredTickUnit(final double axisLength);

    @Override
    public void drawAxis(final GraphicsContext gc, final double axisWidth, final double axisHeight) {
        if ((gc == null) || (getSide() == null)) {
            return;
        }

        drawAxisPre();

        final double axisLength = getSide().isHorizontal() ? axisWidth : axisHeight;

        if (!isTickMarkVisible()) {
            // draw axis title w/o major TickMark
            drawAxisLabel(gc, axisWidth, axisHeight, getAxisLabel(), getTickLength());
            drawAxisLine(gc, axisLength, axisWidth, axisHeight);
            drawAxisPost();
            return;
        }

        updateMinorTickMarks();
        updateCachedVariables();

        final ObservableList<TickMark> majorTicks = getTickMarks();
        final ObservableList<TickMark> minorTicks = getMinorTickMarks();

        // neededLength assumes tick-mark width of one, needed to suppress minor
        // ticks if tick-mark pixel are overlapping
        final double neededLength = (getTickMarks().size() + minorTicks.size()) * 2.0;
        // Don't draw minor tick marks if there isn't enough space for them!
        if (isMinorTickVisible() && (axisLength > neededLength)) {
            updateTickMarkPositions(minorTickMarks);
            drawTickMarks(gc, axisLength, axisWidth, axisHeight, minorTicks, getMinorTickLength(), getMinorTickStyle());
            drawTickLabels(gc, axisWidth, axisHeight, minorTicks, getMinorTickLength());
        }

        // draw major tick-mark over minor tick-marks so that the visible
        // (long) line along the axis with the style of the major-tick is
        // visible
        updateTickMarkPositions(majorTickMarks);
        hideCollidingLabels(majorTickMarks);

        // computeTickMarksForLength(getLength());

        drawTickMarks(gc, axisLength, axisWidth, axisHeight, majorTicks, getTickLength(), getMajorTickStyle());
        drawTickLabels(gc, axisWidth, axisHeight, majorTicks, getTickLength());

        // draw axis title and dominant line
        drawAxisLabel(gc, axisWidth, axisHeight, getAxisLabel(), getTickLength());
        drawAxisLine(gc, axisLength, axisWidth, axisHeight);

        drawAxisPost();
    }

    /**
     * Notifies listeners that the data has been invalidated. If the data is added to the chart, it triggers repaint.
     */
    @Override
    public void fireInvalidated() {
        synchronized (autoNotification()) {
            if (!autoNotification().get() || updateEventListener().isEmpty()) {
                return;
            }
        }

        if (Platform.isFxApplicationThread()) {
            this.invokeListener(new AxisChangeEvent(this), false);
        } else {
            Platform.runLater(() -> this.invokeListener(new AxisChangeEvent(this), false));
        }
    }

    @Override
    public void forceRedraw() {
        invalidateCaches();
        invalidate();
    }

    public AxisLabelFormatter getAxisLabelFormatter() {
        return axisFormatter.get();
    }

    @Override
    public Canvas getCanvas() {
        return canvas;
    }

    // some protection overwrites
    @Override
    public double getDisplayPosition(final double value) {
        return cachedOffset + ((value - getMin()) * getScale());
    }

    public GraphicsContext getGraphicsContext() {
        return canvas.getGraphicsContext2D();
    }

    /**
     * Get the string label name for a tick mark with the given value
     *
     * @param value The value to format into a tick label string
     * @return A formatted string for the given value
     */
    @Override
    public String getTickMarkLabel(final double value) {
        // convert value according to scale factor
        final double scaledValue = value / getUnitScaling();

        final StringConverter<Number> formatter = getTickLabelFormatter();
        if (formatter != null) {
            return formatter.toString(scaledValue);
        }
        // use AxisLabelFormatter based implementation
        return getAxisLabelFormatter().toString(scaledValue);
    }

    /**
     * Get the display position of the zero line along this axis.
     *
     * @return display position or Double.NaN if zero is not in current range;
     */
    @Override
    public double getZeroPosition() {
        if ((0 < getMin()) || (0 > getMax())) {
            return Double.NaN;
        }
        return getDisplayPosition(0.0);
    }

    public void invalidateCaches() {
        getTickMarkValues().clear();
        getMinorTickMarkValues().clear();
        getTickMarks().clear();
        getMinorTickMarks().clear();
    }

    /**
     * Called when data has changed and the range may not be valid anymore. This is only called by the chart if
     * isAutoRanging() returns true. If we are auto ranging it will cause layout to be requested and auto ranging to
     * happen on next layout pass.
     */
    @Override
    public void invalidateRange() {
        final boolean oldState = autoNotification().getAndSet(false);
        final AxisRange autoRange = autoRange(getLength()); // derived axes may potentially pad and round limits
        if (set(autoRange.getMin(), autoRange.getMax())) {
            getAutoRange().setAxisLength(getLength() == 0 ? 1 : getLength(), getSide());
            //setScale(getAutoRange().getScale());
            setScale(calculateNewScale(getLength(), autoRange.getMin(), autoRange.getMax()));
            updateAxisLabelAndUnit();
            // update cache in derived classes
            updateCachedVariables();
            invalidate();
        }

        autoNotification().set(oldState);
        invokeListener(new AxisChangeEvent(this));
    }

    public boolean isLabelOverlapping() {
        // needed for diagnostics purposes
        return labelOverlap;
    }

    /**
     * Checks if the given value is plottable on this axis
     *
     * @param value The value to check if its on axis
     * @return true if the given value is plottable on this axis
     */
    @Override
    public boolean isValueOnAxis(final double value) {
        return Double.isFinite(value) && (value >= getMin()) && (value <= getMax());
    }

    /**
     * on auto-ranging this returns getAutoRange(), otherwise the user-specified range getUserRange() (ie. limits based
     * on [lower,upper]Bound)
     *
     * @return actual range that is being used.
     */
    @Override
    public AxisRange getRange() {
        if (isAutoRanging() || isAutoGrowRanging()) {
            return autoRange(getLength());
        }
        return getUserRange();
    }

    /**
     * Request that the axis is laid out in the next layout pass. This replaces requestLayout() as it has been
     * overridden to do nothing so that changes to children's bounds etc do not cause a layout. This was done as a
     * optimisation as the Axis knows the exact minimal set of changes that really need layout to be updated. So we only
     * want to request layout then, not on any child change.
     */
    @Override
    public void requestAxisLayout() {
        super.requestLayout();
    }

    public void setAxisLabelFormatter(final AxisLabelFormatter value) {
        axisFormatter.set(value);
    }

    @Override
    public boolean setMax(final double value) {
        if (isLogAxis() && ((value <= 0) || !Double.isFinite(value))) {
            if (getMin() >= 0) {
                return super.setMax(getMin() * 1.0E6);
            }
            return false;
        }
        return super.setMax(value);
    }

    @Override
    public boolean setMin(final double value) {
        if (isLogAxis() && ((value <= 0) || !Double.isFinite(value))) {
            if (getMax() > 0) {
                return super.setMin(getMax() / 1.0E6);
            }
            return false;
        }
        return super.setMin(value);
    }

    /**
     * This calculates the upper and lower bound based on the data provided to invalidateRange() method. This must not
     * affect the state of the axis, changing any properties of the axis. Any results of the auto-ranging should be
     * returned in the range object. This will we passed to set(Range) if it has been decided to adopt this range for
     * this axis.
     *
     * @param length The length of the axis in screen coordinates
     * @return Range information, this is implementation dependent
     */
    protected AxisRange autoRange(final double length) {
        // guess a sensible starting size for label size, that is approx 2 lines vertically or 2 charts horizontally
        if (isAutoRanging() || isAutoGrowRanging()) {
            // guess a sensible starting size for label size, that is approx 2 lines vertically or 2 charts horizontally
            final double labelSize = getTickLabelFont().getSize() * 1.2; // N.B. was '2' in earlier implementations
            return autoRange(getAutoRange().getMin(), getAutoRange().getMax(), length, labelSize);
        }
        return getRange();
    }

    protected abstract AxisRange autoRange(final double minValue, final double maxValue, final double length, final double labelSize);

    /**
     * Calculate a list of all the data values for each tick mark in range
     *
     * @param range A range object returned from autoRange()
     * @return A list of tick marks that fit along the axis if it was the given length
     */
    protected abstract List<Double> calculateMajorTickValues(AxisRange range);

    /**
     * Calculate a list of the data values for every minor tick mark
     *
     * @return List of data values where to draw minor tick marks
     */

    protected abstract List<Double> calculateMinorTickValues();

    /**
     * Calculate a new scale for this axis. This should not effect any state(properties) of this axis.
     *
     * @param length The display length of the axis
     * @param lowerBound The lower bound value
     * @param upperBound The upper bound value
     * @return new scale to fit the range from lower bound to upper bound in the given display length
     */
    protected double calculateNewScale(final double length, final double lowerBound, final double upperBound) {
        double newScale;
        final var side = getSide();
        final double diff = upperBound - lowerBound;
        if (side.isVertical()) {
            newScale = diff == 0 ? -length : -(length / diff);
        } else { // HORIZONTAL
            newScale = (upperBound - lowerBound) == 0 ? length : length / diff;
        }
        return newScale == 0 ? -1.0 : newScale;
    }

    protected void clearAxisCanvas(final GraphicsContext gc, final double width, final double height) {
        gc.clearRect(0, 0, width, height);
    }

    /**
     * Computes the preferred height of this axis for the given width. If axis orientation is horizontal, it takes into
     * account the tick mark length, tick label gap and label height.
     *
     * @return the computed preferred width for this axis
     */
    @Override
    protected double computePrefHeight(final double width) {
        final var side = getSide();
        if ((side == null) || (side == Side.CENTER_HOR) || side.isVertical()) {
            // default axis size for uninitalised axis
            return 150;
        }
        return computePrefLength(width);
    }

    /**
     * Computes the preferred width of this axis for the given height. If axis orientation is vertical, it takes into
     * account the tick mark length, tick label gap and label height.
     *
     * @return the computed preferred width for this axis
     */
    @Override
    protected double computePrefWidth(final double height) {
        final var side = getSide();
        if ((side == null) || (side == Side.CENTER_VER) || side.isHorizontal()) {
            // default axis size for uninitalised axis
            return 150;
        }
        return computePrefLength(height);
    }

    // Note: elements need to match what gets drawn in drawAxis!
    private double computePrefLength(final double maxAxisLength) {

        boolean isHorizontal = getSide().isHorizontal();
        maxLabelHeight = 0;
        maxLabelWidth = 0;
        scaleFont = 1.0;
        labelOverlap = false;

        // Size of the axis label w/ units
        double axisLabelLength = 0;
        updateAxisLabelAndUnit(); // TODO: is there a better spot to do it?
        final Text axisLabel = getAxisLabel();
        if (axisLabel.getText() != null && !axisLabel.getText().isBlank()) {
            var bounds = axisLabel.getBoundsInParent();
            axisLabelLength = (isHorizontal ? bounds.getHeight() : bounds.getWidth()) + 2 * getAxisLabelGap();
        }

        // Draws only a line w/ label, so we don't need any cached data
        if (!isTickMarkVisible()) {
            return axisLabelLength + getMajorTickStyle().getStrokeWidth() + getTickLabelGap();
        }

        // TODO: can we cache here? what would be a valid condition?
        /*if (!isNeedsLayout() && Double.isFinite(cachedPrefLength)) {
            return cachedPrefLength;
        }*/

        // TODO: is there a better location for generating the major tick marks?
        setTickUnit(isAutoRanging() || isAutoGrowRanging() ? computePreferredTickUnit(maxAxisLength) : getUserTickUnit());
        updateMajorTickMarks(getRange());

        // Maximum length of the tick labels
        double tickLabelLength = 0;
        if (isTickLabelsVisible()) {

            // Figure out maximum sizes
            double totalLabelsSize = 0;
            double maxLabelSize = 0;
            for (TickMark tickMark : getTickMarks()) {
                tickMark.setVisible(true); // default to all visible
                var tickSize = (isHorizontal ? tickMark.getWidth() : tickMark.getHeight())
                        + 2 * getTickLabelSpacing(); // TODO: copied from before. Is this really 2x for each?
                totalLabelsSize += tickSize;
                maxLabelSize = Math.max(maxLabelSize, tickSize);
                maxLabelHeight = Math.max(maxLabelHeight, tickMark.getHeight());
                maxLabelWidth = Math.max(maxLabelWidth, tickMark.getWidth());
            }

            // '+1' tick label more because first and last tick are half outside axis length
            final double projectedLengthFromIndividualMarks = (majorTickMarks.size() + 1) * maxLabelSize;

            switch (getOverlapPolicy()) {
                case NARROW_FONT:
                    final double scale = maxAxisLength / projectedLengthFromIndividualMarks;
                    if ((scale >= MIN_NARROW_FONT_SCALE) && (scale <= MAX_NARROW_FONT_SCALE)) {
                        scaleFont = scale;
                        break;
                    }
                    scaleFont = Math.min(Math.max(scale, MIN_NARROW_FONT_SCALE), MAX_NARROW_FONT_SCALE);
                    // TODO: should this change the max width/height numbers?
                    // fall through to SKIP_ALT
                    // $FALL-THROUGH$
                case SKIP_ALT:
                    var numLabelsToSkip = 0;
                    if ((maxLabelSize > 0) && (maxAxisLength < totalLabelsSize)) {
                        numLabelsToSkip = (int) (projectedLengthFromIndividualMarks / maxAxisLength);
                        labelOverlap = true;
                    }
                    if (numLabelsToSkip > 0) {
                        var tickIndex = 0;
                        for (final TickMark m : majorTickMarks) {
                            if (m.isVisible()) {
                                m.setVisible((tickIndex++ % numLabelsToSkip) == 0);
                            }
                        }
                    }
                    break;
                case FORCED_SHIFT_ALT:
                    labelOverlap = true;
                    break;
                case SHIFT_ALT:
                    // TODO:
                    //   For now we use a simple heuristic, but this should be based on the actual
                    //   display position to support non-linear axes  and non-uniform labels. The
                    //   'getDisplayPosition()' method uses the node size though, and setting that
                    //   may trigger a new layout. Fixing this will need more refactoring.
                    //   previous check based on:
                    //     checkOverlappingLabels(0, 1, majorTickMarks, getSide(), getTickLabelGap(), isInvertedAxis(), false)
                    labelOverlap = projectedLengthFromIndividualMarks > maxAxisLength;
                    break;
            }

            // Add an extra row/col for shifted labels
            tickLabelLength = isHorizontal ? maxLabelHeight : maxLabelWidth;
            if (labelOverlap) {
                tickLabelLength = 2 * tickLabelLength + 3 * getTickLabelGap();
            } else {
                tickLabelLength += 2 * getTickLabelGap();
            }

        }

        // Length of the marks
        double tickLength = 0;
        if (isTickMarkVisible() && getTickLength() > 0) {
            tickLength = getTickLength();
        }

        // Total estimate
        return tickLength + tickLabelLength + axisLabelLength;

    }

    private void hideCollidingLabels(List<TickMark> tickMarks) {
        if (!labelOverlap) {
            checkOverlappingLabels(0, 1, tickMarks, getSide(), getTickLabelGap(), isInvertedAxis(), true);
        } else {
            checkOverlappingLabels(0, 2, tickMarks, getSide(), getTickLabelGap(), isInvertedAxis(), true);
            checkOverlappingLabels(1, 2, tickMarks, getSide(), getTickLabelGap(), isInvertedAxis(), true);
        }
    }

    protected void updateMajorTickMarks(AxisRange range) {
        // TODO: cache if the range and tick units have not changed?
        var newTickValues = calculateMajorTickValues(range);
        if(newTickValues.equals(getTickMarkValues())) {
            return; // no need to update the tick mark labels
        }

        // TODO: old code only updated on >=2 ticks?
        getTickMarkValues().setAll(newTickValues);

        // Update labels
        var formatter = getAxisLabelFormatter();
        formatter.updateFormatter(newTickValues, getUnitScaling());

        // Update the existing mark objects
        List<TickMark> marks = FXUtils.sizedList(getTickMarks(), newTickValues.size(), () -> new TickMark(getTickLabelStyle()));
        int i = 0;
        for (var tick : newTickValues) {
            marks.get(i++).setValue(tick, getTickMarkLabel(tick));
        }
        tickMarksUpdated();

    }

    protected void updateMinorTickMarks() {
        var newTickValues = calculateMinorTickValues();
        if (newTickValues.equals(getMinorTickMarkValues())) {
            return;
        }
        getMinorTickMarkValues().setAll(newTickValues);

        // TODO: do we actually need the TickMark objects? Are they used for anything other than as a cache for the display position?
        List<TickMark> marks = FXUtils.sizedList(getMinorTickMarks(), newTickValues.size(), () -> new TickMark(getTickLabelStyle()));
        int i = 0;
        for (var tick : newTickValues) {
            marks.get(i++).setValue(tick, "");
        }
        tickMarksUpdated();
    }

    protected void updateTickMarkPositions(List<TickMark> tickMarks) {
        for (TickMark tickMark : tickMarks) {
            tickMark.setPosition(getDisplayPosition(tickMark.getValue()));
        }
    }

    /**
     * Computes range of this axis, similarly to {@link #autoRange(double, double, double, double)}. The major
     * difference is that this method is called when {@link #autoRangingProperty() auto-range} is off.
     *
     * @param minValue The min data value that needs to be plotted on this axis
     * @param maxValue The max data value that needs to be plotted on this axis
     * @param axisLength The length of the axis in display coordinates
     * @param labelSize The approximate average size a label takes along the axis
     * @return The calculated range
     * @see #autoRange(double, double, double, double)
     */
    protected abstract AxisRange computeRange(double minValue, double maxValue, double axisLength, double labelSize);

    protected void drawAxisLabel(final GraphicsContext gc, final double axisWidth, final double axisHeight,
                                 final TextStyle axisLabel, final double tickLength) {
        final double paddingX = getSide().isHorizontal() ? getAxisPadding() : 0.0;
        final double paddingY = getSide().isVertical() ? getAxisPadding() : 0.0;
        final boolean isHorizontal = getSide().isHorizontal();
        final double tickLabelGap = getTickLabelGap();
        final double axisLabelGap = getAxisLabelGap();

        // for relative positioning of axes drawn on top of the main canvas
        final double axisCentre = getAxisCenterPosition();
        double labelPosition;
        double labelGap;
        switch (axisLabel.getTextAlignment()) {
        case LEFT:
            labelPosition = 0.0;
            labelGap = +tickLabelGap;
            break;
        case RIGHT:
            labelPosition = 1.0;
            labelGap = -tickLabelGap;
            break;
        case CENTER:
        case JUSTIFY:
        default:
            labelPosition = 0.5;
            labelGap = 0.0;
            break;
        }

        // find largest tick label size (width for horizontal axis, height for
        // vertical axis)
        final double tickLabelSize = isHorizontal ? maxLabelHeight : maxLabelWidth;
        final double shiftedLabels = ((getOverlapPolicy() == AxisLabelOverlapPolicy.SHIFT_ALT) && isLabelOverlapping())
                                                  || (getOverlapPolicy() == AxisLabelOverlapPolicy.FORCED_SHIFT_ALT)
                                           ? tickLabelSize + tickLabelGap
                                           : 0.0;

        // save css-styled label parameters
        gc.save();
        gc.translate(paddingX, paddingY);
        // N.B. streams, filter, and forEach statements have been evaluated and
        // appear to give no (or negative) performance for little/arguable
        // readability improvement (CG complexity from 40 -> 28, but mere
        // numerical number and not actual readability), thus sticking to 'for'
        // loops
        final double x;
        final double y;
        switch (getSide()) {
        case LEFT:
            gc.setTextBaseline(VPos.BASELINE);
            x = axisWidth - tickLength - (2 * tickLabelGap) - tickLabelSize - axisLabelGap - shiftedLabels;
            y = ((1.0 - labelPosition) * axisHeight) - labelGap;
            axisLabel.setRotate(-90);
            break;

        case RIGHT:
            gc.setTextBaseline(VPos.TOP);
            axisLabel.setRotate(-90);
            x = tickLength + tickLabelGap + tickLabelSize + axisLabelGap + shiftedLabels;
            y = ((1.0 - labelPosition) * axisHeight) - labelGap;
            break;

        case TOP:
            gc.setTextBaseline(VPos.BOTTOM);
            x = (labelPosition * axisWidth) + labelGap;
            y = axisHeight - tickLength - tickLabelGap - tickLabelSize - axisLabelGap - shiftedLabels;
            break;

        case BOTTOM:
            gc.setTextBaseline(VPos.TOP);
            x = (labelPosition * axisWidth) + labelGap;
            y = tickLength + tickLabelGap + tickLabelSize + axisLabelGap + shiftedLabels;
            break;

        case CENTER_VER:
            gc.setTextBaseline(VPos.TOP);
            axisLabel.setRotate(-90);
            x = (axisCentre * axisWidth) - tickLength - (2 * tickLabelGap) - tickLabelSize - axisLabelGap - shiftedLabels;
            y = ((1.0 - labelPosition) * axisHeight) - labelGap;
            break;

        case CENTER_HOR:
            gc.setTextBaseline(VPos.TOP);
            x = (labelPosition * axisWidth) + labelGap;
            y = (axisCentre * axisHeight) + tickLength + tickLabelGap + tickLabelSize + axisLabelGap + shiftedLabels;
            break;
        default:
            // N.B. does not occur (all axis side cases handled above) -- will pop up only once adding new definitions
            throw new IllegalStateException("unknown axis side " + getSide());
        }
        drawAxisLabel(gc, x, y, axisLabel);

        gc.restore();
    }

    protected void drawAxisLine(final GraphicsContext gc, final double axisLength, final double axisWidth,
            final double axisHeight) {
        // N.B. axis canvas is (by-design) larger by 'padding' w.r.t.
        // required/requested axis length (needed for nicer label placements on
        // border.
        final double paddingX = getSide().isHorizontal() ? getAxisPadding() : 0.0;
        final double paddingY = getSide().isVertical() ? getAxisPadding() : 0.0;
        // for relative positioning of axes drawn on top of the main canvas
        final double axisCentre = getAxisCenterPosition();

        // save css-styled line parameters
        gc.save();
        getMajorTickStyle().copyStyleTo(gc);

        // N.B. important: translate by padding ie. canvas is +padding larger on
        // all size compared to region
        gc.translate(paddingX, paddingY);
        switch (getSide()) {
        case LEFT:
            // axis line on right side of canvas N.B. 'width - 1' because otherwise snap shifts line outside of canvas
            gc.strokeLine(snap(axisWidth) - 1, snap(0), snap(axisWidth) - 1, snap(axisLength));
            break;
        case RIGHT:
            // axis line on left side of canvas
            gc.strokeLine(snap(0), snap(0), snap(0), snap(axisLength));
            break;
        case TOP:
            // line on bottom side of canvas (N.B. (0,0) is top left corner)
            gc.strokeLine(snap(0), snap(axisHeight) - 1, snap(axisLength), snap(axisHeight) - 1);
            break;
        case BOTTOM:
            // line on top side of canvas (N.B. (0,0) is top left corner)
            gc.strokeLine(snap(0), snap(0), snap(axisLength), snap(0));
            break;
        case CENTER_HOR:
            // axis line at the centre of the canvas
            gc.strokeLine(snap(0), axisCentre * axisHeight, snap(axisLength), snap(axisCentre * axisHeight));

            break;
        case CENTER_VER:
            // axis line at the centre of the canvas
            gc.strokeLine(snap(axisCentre * axisWidth), snap(0), snap(axisCentre * axisWidth), snap(axisLength));

            break;
        default:
            break;
        }
        gc.restore();
    }

    /**
     * function to be executed after the axis has been drawn can be used to execute user-specific code (e.g. update of
     * other classes/properties)
     */
    protected void drawAxisPost() { // NOPMD by rstein function can but does not have to be overwritten
        // to be overwritten in derived classes
    }

    /**
     * function to be executed prior to drawing axis can be used to execute user-specific code (e.g. modifying
     * tick-marks) prior to drawing
     */
    protected void drawAxisPre() { // NOPMD by rstein function can but does not have to be overwritten
        // to be overwritten in derived classes
    }

    protected void drawTickLabels(final GraphicsContext gc, final double axisWidth, final double axisHeight,
            final ObservableList<TickMark> tickMarks, final double tickLength) {
        if ((tickLength <= 0) || tickMarks.isEmpty()) {
            return;
        }
        final double paddingX = getSide().isHorizontal() ? getAxisPadding() : 0.0;
        final double paddingY = getSide().isVertical() ? getAxisPadding() : 0.0;
        // for relative positioning of axes drawn on top of the main canvas
        final double axisCentre = getAxisCenterPosition();
        final AxisLabelOverlapPolicy overlapPolicy = getOverlapPolicy();
        final double tickLabelGap = getTickLabelGap();

        // save css-styled label parameters
        gc.save();
        var style = getTickLabelStyle();
        var fontSize = style.getFont().getSize();
        style.copyStyleTo(gc);

        // N.B. important: translate by padding ie. canvas is +padding larger on
        // all size compared to region
         gc.translate(paddingX, paddingY);
        // N.B. streams, filter, and forEach statements have been evaluated and
        // appear to give no (or negative) performance for little/arguable
        // readability improvement (CG complexity from 40 -> 28, but mere
        // numerical number and not actual readability), thus sticking to 'for'
        // loops

        final var firstTick = tickMarks.get(0);
        int counter = ((int) firstTick.getValue()) % 2;

        switch (getSide()) {
        case LEFT:
            for (final TickMark tickMark : tickMarks) {
                if (!tickMark.isVisible()) {
                    // skip invisible labels
                    continue;
                }

                final double position = tickMark.getPosition();
                double x = axisWidth - tickLength - tickLabelGap;
                switch (overlapPolicy) {
                case DO_NOTHING:
                    drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    break;
                case SHIFT_ALT:
                    if (isLabelOverlapping()) {
                        x -= ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    }
                    drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    break;
                case FORCED_SHIFT_ALT:
                    x -= ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    break;
                case NARROW_FONT:
                case SKIP_ALT:
                default:
                    if (((counter % 2) == 0) || !isLabelOverlapping() || scaleFont < MAX_NARROW_FONT_SCALE) {
                        drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    }
                    break;
                }
                counter++;
            }
            break;

        case RIGHT:
        case CENTER_VER:
            for (final TickMark tickMark : tickMarks) {
                if (!tickMark.isVisible()) {
                    // skip invisible labels
                    continue;
                }

                final double position = tickMark.getPosition();
                double x = tickLength + tickLabelGap;
                if (getSide().equals(Side.CENTER_VER)) {
                    // additional special offset for horizontal centre axis
                    x += axisCentre * axisWidth;
                }
                switch (overlapPolicy) {
                case DO_NOTHING:
                    drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    break;
                case SHIFT_ALT:
                    if (isLabelOverlapping()) {
                        x += ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    }
                    drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    break;
                case FORCED_SHIFT_ALT:
                    x += ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    break;
                case NARROW_FONT:
                case SKIP_ALT:
                default:
                    if (((counter % 2) == 0) || !isLabelOverlapping() || scaleFont < MAX_NARROW_FONT_SCALE) {
                        drawTickMarkLabel(gc, x, position, scaleFont, tickMark);
                    }
                    break;
                }
                counter++;
            }
            break;

        case TOP:
            for (final TickMark tickMark : tickMarks) {
                if (!tickMark.isVisible()) {
                    // skip invisible labels
                    continue;
                }

                final double position = tickMark.getPosition();
                double y = axisHeight - tickLength - tickLabelGap;
                switch (overlapPolicy) {
                case DO_NOTHING:
                    drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    break;
                case SHIFT_ALT:
                    if (isLabelOverlapping()) {
                        y -= ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    }
                    drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    break;
                case FORCED_SHIFT_ALT:
                    y -= ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    break;
                case NARROW_FONT:
                case SKIP_ALT:
                default:
                    if (((counter % 2) == 0) || !isLabelOverlapping() || scaleFont < MAX_NARROW_FONT_SCALE) {
                        drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    }
                    break;
                }
                counter++;
            }
            break;

        case BOTTOM:
        case CENTER_HOR:
            for (final TickMark tickMark : tickMarks) {
                if (!tickMark.isVisible()) {
                    // skip invisible labels
                    continue;
                }

                final double position = tickMark.getPosition();
                double y = tickLength + tickLabelGap;
                if (getSide().equals(Side.CENTER_HOR)) {
                    // additional special offset for horizontal centre axis
                    y += axisCentre * axisHeight;
                }

                switch (overlapPolicy) {
                case DO_NOTHING:
                    drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    break;
                case SHIFT_ALT:
                    if (isLabelOverlapping()) {
                        y += ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    }
                    drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    break;
                case FORCED_SHIFT_ALT:
                    y += ((counter % 2) * tickLabelGap) + ((counter % 2) * fontSize);
                    drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    break;
                case NARROW_FONT:
                case SKIP_ALT:
                default:
                    if (((counter % 2) == 0) || !isLabelOverlapping() || scaleFont < MAX_NARROW_FONT_SCALE) {
                        drawTickMarkLabel(gc, position, y, scaleFont, tickMark);
                    }
                    break;
                }
                counter++;
            }
            break;
        default:
            break;
        }

        gc.restore();
    }

    protected void drawTickMarks(final GraphicsContext gc, final double axisLength, final double axisWidth,
            final double axisHeight, final ObservableList<TickMark> tickMarks, final double tickLength,
            final PathStyle tickStyle) {
        if (tickLength <= 0) {
            return;
        }
        final double paddingX = getSide().isHorizontal() ? getAxisPadding() : 0.0;
        final double paddingY = getSide().isVertical() ? getAxisPadding() : 0.0;
        // for relative positioning of axes drawn on top of the main canvas
        final double axisCentre = getAxisCenterPosition();

        gc.save();
        // save css-styled line parameters
        tickStyle.copyStyleTo(gc);
        // N.B. important: translate by padding ie. canvas is +padding larger on all size compared to region
        gc.translate(paddingX, paddingY);

        // N.B. streams, filter, and forEach statements have been evaluated and
        // appear to give no (or negative) performance for little/arguable
        // readability improvement (CG complexity from 40 -> 28, but mere
        // numerical number and not actual readability), thus sticking to 'for'
        // loops
        switch (getSide()) {
        case LEFT:
            // draw trick-lines towards left w.r.t. axis line
            for (final TickMark tickMark : tickMarks) {
                final double position = tickMark.getPosition();
                if ((position < 0) || (position > axisLength)) {
                    // skip tick-marks outside the nominal axis length
                    continue;
                }
                final double x0 = snap(axisWidth - tickLength);
                final double x1 = snap(axisWidth);
                final double y = snap(position);
                gc.strokeLine(x0, y, x1, y);
            }
            break;

        case RIGHT:
            // draw trick-lines towards right w.r.t. axis line
            for (final TickMark tickMark : tickMarks) {
                final double position = tickMark.getPosition();
                if ((position < 0) || (position > axisLength)) {
                    // skip tick-marks outside the nominal axis length
                    continue;
                }
                final double x0 = snap(0);
                final double x1 = snap(tickLength);
                final double y = snap(position);
                gc.strokeLine(x0, y, x1, y);
            }

            break;

        case TOP:
            // draw trick-lines upwards from axis line
            for (final TickMark tickMark : tickMarks) {
                final double position = tickMark.getPosition();
                if ((position < 0) || (position > axisLength)) {
                    // skip tick-marks outside the nominal axis length
                    continue;
                }
                final double x = snap(position);
                final double y0 = snap(axisHeight);
                final double y1 = snap(axisHeight - tickLength);
                gc.strokeLine(x, y0, x, y1);
            }
            break;

        case BOTTOM:
            // draw trick-lines downwards from axis line
            for (final TickMark tickMark : tickMarks) {
                final double position = tickMark.getPosition();
                if ((position < 0) || (position > axisLength)) {
                    // skip tick-marks outside the nominal axis length
                    continue;
                }
                final double x = snap(position);
                final double y0 = snap(0);
                final double y1 = snap(tickLength);
                gc.strokeLine(x, y0, x, y1);
            }
            break;

        case CENTER_HOR:
            // draw symmetric trick-lines around axis line
            for (final TickMark tickMark : tickMarks) {
                final double position = tickMark.getPosition();
                if ((position < 0) || (position > axisLength)) {
                    // skip tick-marks outside the nominal axis length
                    continue;
                }
                final double x = snap(position);
                final double y0 = snap((axisCentre * axisHeight) - tickLength);
                final double y1 = snap((axisCentre * axisHeight) + tickLength);
                gc.strokeLine(x, y0, x, y1);
            }
            break;

        case CENTER_VER:
            // draw symmetric trick-lines around axis line
            for (final TickMark tickMark : tickMarks) {
                final double position = tickMark.getPosition();
                if ((position < 0) || (position > axisLength)) {
                    // skip tick-marks outside the nominal axis length
                    continue;
                }
                final double x0 = snap((axisCentre * axisWidth) - tickLength);
                final double x1 = snap((axisCentre * axisWidth) + tickLength);
                final double y = snap(position);
                gc.strokeLine(x0, y, x1, y);
            }
            break;
        default:
            break;
        }

        gc.restore();
    }

    /**
     * Invoked during the layout pass to layout this axis and all its content.
     * A layout is triggered on width/height changes or when a layout
     * has been requested. Simply moving a node does not cause a layout.
     */
    @Override
    protected void layoutChildren() {
        // full-size the canvas. The axis gets drawn from the Chart
        // to guarantee ordering (e.g. ticks are available before the grid)
        canvas.resizeRelocate(0, 0, getWidth(), getHeight());
        needsToBeDrawn = true;
        // TODO: support canvas padding
    }

    private boolean needsToBeDrawn = true;

    @Override
    public void drawAxis() {
        // Try using the cached content
        if (!needsToBeDrawn) return;
        needsToBeDrawn = false;

        // draw minor / major tick marks on canvas
        final var gc = canvas.getGraphicsContext2D();
        clearAxisCanvas(gc, canvas.getWidth(), canvas.getHeight());
        drawAxis(gc, getWidth(), getHeight());
    }

    private static boolean checkOverlappingLabels(final int start, final int stride,
            List<TickMark> majorTickMarks, Side side, double gap, boolean isInverted, boolean makeInvisible) {
        var labelHidden = false;
        TickMark lastVisible = null;
        for (int i = start; i < majorTickMarks.size(); i += stride) {
            final var current = majorTickMarks.get(i);
            if (!current.isVisible()) {
                continue;
            }
            if (lastVisible == null || !isTickLabelsOverlap(side, isInverted, lastVisible, current, gap)) {
                lastVisible = current;
            } else {
                labelHidden = true;
                current.setVisible(!makeInvisible);
            }
        }
        return labelHidden;
    }

    protected double measureTickMarkLength(final Double major) {
        // N.B. this is a known performance hot-spot -> start optimisation here
        tmpTickMark.setValue(major, getTickMarkLabel(major));
        return getSide().isHorizontal() ? tmpTickMark.getWidth() : tmpTickMark.getHeight();
    }

    private final TickMark tmpTickMark = new TickMark(getTickLabelStyle());

    /**
     * Sets the alignment for rotated label, i.e., determines whether to write
     * bottom-left to top-right or top-left to bottom-right. Depends on the side
     * and the angle of rotation.
     */
    protected void updateTickLabelAlignment() {
        // normalise rotation to [-360, +360]
        final int rotation = ((int) getTickLabelRotation() % 360);
        var style = getTickLabelStyle();
        var alignment = style.getTextAlignment();
        var origin = style.getTextOrigin();
        switch (getSide()) {
            case TOP:
                alignment = TextAlignment.CENTER;
                origin = VPos.BOTTOM;
                //special alignment treatment if axes labels are to be rotated
                if ((rotation != 0) && ((rotation % 90) == 0)) {
                    alignment = TextAlignment.LEFT;
                    origin = VPos.CENTER;
                } else if ((rotation % 90) != 0) {
                    // pivoting point to left-bottom label corner
                    alignment = TextAlignment.LEFT;
                    origin = VPos.BOTTOM;
                }
                break;
            case BOTTOM:
            case CENTER_HOR:
                alignment = TextAlignment.CENTER;
                origin = VPos.TOP;
                // special alignment treatment if axes labels are to be rotated
                if ((rotation != 0) && ((rotation % 90) == 0)) {
                    alignment = TextAlignment.LEFT;
                    origin = VPos.CENTER;
                } else if ((rotation % 90) != 0) {
                    // pivoting point to left-top label corner
                    alignment = TextAlignment.LEFT;
                    origin = VPos.TOP;
                }
                break;
            case LEFT:
                alignment = TextAlignment.RIGHT;
                origin = VPos.CENTER;
                // special alignment treatment if axes labels are to be rotated
                if ((rotation != 0) && ((rotation % 90) == 0)) {
                    alignment = TextAlignment.CENTER;
                    origin = VPos.BOTTOM;
                }
                break;
            case RIGHT:
            case CENTER_VER:
                alignment = TextAlignment.LEFT;
                origin = VPos.CENTER;
                // special alignment treatment if axes labels are to be rotated
                if ((rotation != 0) && ((rotation % 90) == 0)) {
                    alignment = TextAlignment.CENTER;
                    origin = VPos.TOP;
                }
                break;
            default:
        }

        // Update values
        if(alignment != style.getTextAlignment()) {
            style.setTextAlignment(alignment);
        }
        if(origin != style.getTextOrigin()) {
            style.setTextOrigin(origin);
        }

    }

    /**
     * This is used to check if any given animation should run. It returns true if animation is enabled and the node is
     * visible and in a scene.
     *
     * @return true if animations should happen
     */
    protected boolean shouldAnimate() {
        return isAnimated() && (getScene() != null);
    }

    /**
     * Called during layout if the tickmarks have been updated, allowing subclasses to do anything they need to in
     * reaction.
     */
    protected void tickMarksUpdated() { // NOPMD by rstein function can but does not have to be overwritten
    }

    /**
     * Checks if two consecutive tick mark labels overlaps.
     *
     * @param side side of the Axis
     * @param m1 first tick mark
     * @param m2 second tick mark
     * @param gap minimum space between labels
     * @return true if labels overlap
     */
    private static boolean isTickLabelsOverlap(final Side side, final boolean isInverted, final TickMark m1,
            final TickMark m2, final double gap) {
        final double m1Size = side.isHorizontal() ? m1.getWidth() : m1.getHeight();
        final double m2Size = side.isHorizontal() ? m2.getWidth() : m2.getHeight();
        final double m1Start = m1.getPosition() - (m1Size / 2);
        final double m1End = m1.getPosition() + (m1Size / 2);
        final double m2Start = m2.getPosition() - (m2Size / 2);
        final double m2End = m2.getPosition() + (m2Size / 2);
        return side.isVertical() && !isInverted ? Math.abs(m1Start - m2End) <= gap : Math.abs(m2Start - m1End) <= gap;
    }

    protected static void drawAxisLabel(final GraphicsContext gc, final double x, final double y, final TextStyle label) {
        gc.save();
        label.copyStyleTo(gc);
        gc.translate(x, y);
        gc.fillText(label.getText(), 0, 0);
        gc.restore();
    }

    protected static void drawTickMarkLabel(final GraphicsContext gc, final double x, final double y,
                                            final double scaleFont, final TickMark tickMark) {
        // Needs the style to be set beforehand
        gc.fillText(tickMark.getText(), x, y);
        // TODO: should we also support strokeText() for outlined text? maybe defaults to transparent?
    }

    /**
     * @param coordinate double coordinate to snapped to actual pixel index
     * @return coordinate that is snapped to pixel (for a 'crisper' display)
     */
    protected static double snap(final double coordinate) {
        return Math.round(coordinate) + 0.5;
    }

}

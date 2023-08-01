package io.fair_acc.chartfx;

import java.security.InvalidParameterException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.fair_acc.chartfx.ui.layout.ChartPane;
import io.fair_acc.chartfx.ui.layout.PlotAreaPane;
import io.fair_acc.chartfx.ui.*;
import io.fair_acc.chartfx.ui.utils.LayoutHook;
import io.fair_acc.dataset.event.EventSource;
import io.fair_acc.dataset.events.BitState;
import io.fair_acc.dataset.events.ChartBits;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.*;
import javafx.geometry.*;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Paint;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fair_acc.chartfx.axes.Axis;
import io.fair_acc.chartfx.axes.spi.AbstractAxis;
import io.fair_acc.chartfx.axes.spi.DefaultNumericAxis;
import io.fair_acc.chartfx.legend.Legend;
import io.fair_acc.chartfx.legend.spi.DefaultLegend;
import io.fair_acc.chartfx.plugins.ChartPlugin;
import io.fair_acc.chartfx.renderer.Renderer;
import io.fair_acc.chartfx.renderer.spi.LabelledMarkerRenderer;
import io.fair_acc.chartfx.ui.css.CssPropertyFactory;
import io.fair_acc.chartfx.ui.geometry.Side;
import io.fair_acc.chartfx.utils.FXUtils;
import io.fair_acc.dataset.DataSet;
import io.fair_acc.dataset.utils.AssertUtils;
import io.fair_acc.dataset.utils.NoDuplicatesList;
import io.fair_acc.dataset.utils.ProcessingProfiler;

/**
 * Chart designed primarily to display data traces using DataSet interfaces which are more flexible and efficient than
 * the observable lists used by XYChart. Brief history: original design inspired by Oracle, extended by CERN (i.e.
 * plugin concept/zoomer), modified to mitigate JavaFX performance issues and extended renderer
 * concept/canvas-concept/interfaces/+more plugins by GSI. Refactored and re-write in 2018 to make it compatible with
 * GPLv3 which -- in the spirit of 'Ship of Theseus' -- makes it de-facto a new development. Contributions, bug-fixes,
 * and modifications are welcome. Hope you find this library useful and enjoy!
 *
 * @author original conceptual design by Oracle (2010, 2014)
 * @author hbraeun, rstein, major refactoring, re-implementation and re-design
 */
public abstract class Chart extends Region implements EventSource {
    private final LayoutHook layoutHooks = LayoutHook.newPreAndPostHook(this, this::runPreLayout, this::runPostLayout);

    // The chart has two different states, one that includes everything and is only ever on the JavaFX thread, and
    // a thread-safe one that receives dataSet updates and forwards them on the JavaFX thread.
    protected final BitState state = BitState.initDirty(this, BitState.ALL_BITS)
            .addChangeListener(ChartBits.KnownMask, (src, bits) -> layoutHooks.registerOnce())
            .addChangeListener(ChartBits.ChartLayout, (src, bits) -> super.requestLayout());

    // DataSets are the only part that can potentially get updated from different threads, so we use a separate
    // state object that can handle multithreaded updates. The state always represents the current aggregate state
    // of all datasets, but the JavaFX change listener may not forward the dirty bits to the chart until the next frame.
    // This creates a race condition where delta bits that are already cleared in the datasets may end up dirtying the
    // chart and trigger an unnecessary redraw. To avoid this issue we ignore the delta and pass the current state.
    protected final BitState dataSetState = BitState.initDirtyMultiThreaded(this, BitState.ALL_BITS)
            .addChangeListener(FXUtils.runOnFxThread((src, deltaBits) -> state.setDirty(src.getBits())));

    private static final Logger LOGGER = LoggerFactory.getLogger(Chart.class);
    private static final String CHART_CSS = Objects.requireNonNull(Chart.class.getResource("chart.css")).toExternalForm();
    private static final CssPropertyFactory<Chart> CSS = new CssPropertyFactory<>(Control.getClassCssMetaData());
    private static final int DEFAULT_TRIGGER_DISTANCE = 50;
    protected static final boolean DEBUG = Boolean.getBoolean("chartfx.debug"); // for more verbose debugging

    protected final BooleanProperty showing = new SimpleBooleanProperty(this, "showing", false);
    {
        showing.bind(FXUtils.getShowingBinding(this));
    }

    /**
     * When true any data changes will be animated.
     */
    private final BooleanProperty animated = new SimpleBooleanProperty(this, "animated", true);
    // TODO: Check whether 'this' or chart contents need to be added
    /**
     * Animator for animating stuff on the chart
     */
    protected final ChartLayoutAnimator animator = new ChartLayoutAnimator(this);

    /**
     * When true the chart will display a legend if the chart implementation supports a legend.
     */
    private final StyleableBooleanProperty legendVisible = CSS.createBooleanProperty(this, "legendVisible", true, state.onAction(ChartBits.ChartLegend));

    protected final ObservableList<Axis> axesList = FXCollections.observableList(new NoDuplicatesList<>());
    private final Map<ChartPlugin, Group> pluginGroups = new HashMap<>();
    private final ObservableList<ChartPlugin> plugins = FXCollections.observableList(new LinkedList<>());
    private final ObservableList<DataSet> datasets = FXCollections.observableArrayList();
    protected final ObservableList<DataSet> allDataSets = FXCollections.observableArrayList();
    private final ObservableList<Renderer> renderers = FXCollections.observableArrayList();
    {
        getRenderers().addListener(this::rendererChanged);
    }

    // Inner canvas for the drawn content
    protected final ResizableCanvas canvas = new ResizableCanvas();
    protected final Pane canvasForeground = new Pane();
    protected final Group pluginsArea = Chart.createChildGroup();

    // Area where plots get drawn
    protected final Pane plotBackground = new Pane();
    protected final HiddenSidesPane plotArea = new HiddenSidesPane();
    protected final Pane plotForeGround = new Pane();

    // Outer chart elements
    protected final ChartPane measurementPane = new ChartPane();
    protected final ChartPane titleLegendPane = new ChartPane();
    protected final ChartPane axesAndCanvasPane = new ChartPane();

    // Outer area with hidden toolbars
    protected final HiddenSidesPane menuPane = new HiddenSidesPane();
    protected final ToolBarFlowPane toolBar = new ToolBarFlowPane(this);
    protected final BooleanProperty toolBarPinned = new SimpleBooleanProperty(this, "toolBarPinned", false);

    {
        // Build hierarchy
        // > menuPane (hidden toolbars that slide in from top/bottom)
        //   > measurement pane (labels/menus for working with data)
        //     > legend & title pane (static legend and title)
        //       > axis pane (x/y axes)
        //         > axes
        //         > plot area (plotted content, hidden elements for zoom etc.)
        //           > canvas (main)
        //           > canvas foreground
        //           > plugins
        //         > plot background/foreground
        plotArea.setContent(new PlotAreaPane(getCanvas(), getCanvasForeground(), pluginsArea));
        axesAndCanvasPane.addCenter(getPlotBackground(), getPlotArea(), getPlotForeground());
        titleLegendPane.addCenter(axesAndCanvasPane);
        measurementPane.addCenter(titleLegendPane);
        menuPane.setContent(measurementPane);
        getChildren().add(menuPane);
    }

    protected final ListChangeListener<Axis> axesChangeListenerLocal = this::axesChangedLocal;
    protected final ListChangeListener<Axis> axesChangeListener = this::axesChanged;
    protected final ListChangeListener<DataSet> datasetChangeListener = this::datasetsChanged;
    protected final ListChangeListener<ChartPlugin> pluginsChangedListener = this::pluginsChanged;

    {
        getDatasets().addListener(datasetChangeListener);
        getAxes().addListener(axesChangeListener);
        getAxes().addListener(axesChangeListenerLocal);
        sceneProperty().addListener((observable, oldValue, newValue) -> {
            // Render for the new scene. Note that the scene reference gets
            // set in the CSS phase, so by the time we can register it would
            // already be too late. Waiting for the layout phase wouldn't
            // let us change the scene graph, so the best option we have is
            // run the pre layout hook manually during CSS.
            layoutHooks.runPreLayoutAndAdd();
        });
    }

    protected final Label titleLabel = new Label();

    protected final StringProperty title = new StringPropertyBase() {
        @Override
        public Object getBean() {
            return Chart.this;
        }

        @Override
        public String getName() {
            return "title";
        }

        @Override
        protected void invalidated() {
            titleLabel.setText(get());
        }
    };

    /**
     * The side of the chart where the title is displayed default Side.TOP
     */
    private final StyleableObjectProperty<Side> titleSide = CSS.createObjectProperty(this, "titleSide", Side.TOP, false,
            StyleConverter.getEnumConverter(Side.class), (oldVal, newVal) -> {
                AssertUtils.notNull("Side must not be null", newVal);
                ChartPane.setSide(titleLabel, newVal);
                return newVal;
            }, state.onAction(ChartBits.ChartLayout));

    /**
     * The side of the chart where the legend should be displayed default value Side.BOTTOM
     */
    private final StyleableObjectProperty<Side> legendSide = CSS.createObjectProperty(this, "legendSide", Side.BOTTOM, false,
            StyleConverter.getEnumConverter(Side.class), (oldVal, newVal) -> {
                AssertUtils.notNull("Side must not be null", newVal);

                final Legend legend = getLegend();
                if (legend == null) {
                    return newVal;
                }
                ChartPane.setSide(legend.getNode(), newVal);
                legend.setVertical(newVal.isVertical());

                return newVal;
            }, state.onAction(ChartBits.ChartLayout));

    /**
     * The node to display as the Legend. Subclasses can set a node here to be displayed on a side as the legend. If no
     * legend is wanted then this can be set to null
     */
    private final ObjectProperty<Legend> legend = new SimpleObjectProperty<>(this, "legend", new DefaultLegend()) {
        private Legend oldLegend = get();
        {
            getTitleLegendPane().addSide(getLegendSide(), oldLegend.getNode());
        }

        @Override
        protected void invalidated() {
            Legend newLegend = get();

            if (oldLegend != null) {
                getTitleLegendPane().remove(oldLegend.getNode());
            }

            if (newLegend != null) {
                newLegend.getNode().setVisible(isLegendVisible());
                getTitleLegendPane().addSide(getLegendSide(), newLegend.getNode());
            }
            super.set(newLegend);
            oldLegend = newLegend;
            updateLegend(getDatasets(), getRenderers());
        }
    };

    private final StyleableObjectProperty<Side> toolBarSide = CSS.createObjectProperty(this, "toolBarSide", Side.TOP, false,
            StyleConverter.getEnumConverter(Side.class), (oldVal, newVal) -> {
                AssertUtils.notNull("Side must not be null", newVal);
                // remove tool bar from potential other chart side pane locations
                menuPane.setTop(null);
                menuPane.setBottom(null);
                menuPane.setLeft(null);
                menuPane.setRight(null);
                switch (newVal) {
                case LEFT:
                    getToolBar().setOrientation(Orientation.VERTICAL);
                    menuPane.setLeft(getToolBar());
                    break;
                case RIGHT:
                    getToolBar().setOrientation(Orientation.VERTICAL);
                    menuPane.setRight(getToolBar());
                    break;
                case BOTTOM:
                    getToolBar().setOrientation(Orientation.HORIZONTAL);
                    menuPane.setBottom(getToolBar());
                    break;
                case TOP:
                default:
                    getToolBar().setOrientation(Orientation.HORIZONTAL);
                    menuPane.setTop(getToolBar());
                    break;
                }
                return (newVal);
            });

    /**
     * Creates a new default Chart instance.
     *
     * @param axes axes to be added to the chart
     */
    public Chart(Axis... axes) {
        for (int dim = 0; dim < axes.length; dim++) {
            final Axis axis = axes[dim];
            if (!(axis instanceof AbstractAxis)) {
                continue;
            }
            final AbstractAxis abstractAxis = (AbstractAxis) axis;
            if (abstractAxis.getDimIndex() < 0) {
                abstractAxis.setDimIndex(dim);
            }
        }

        menuPane.setTriggerDistance(Chart.DEFAULT_TRIGGER_DISTANCE);
        setMinSize(0, 0);
        setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        setMaxSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        setPadding(Insets.EMPTY);

        plotBackground.toBack();
        plotForeGround.toFront();
        plotForeGround.setMouseTransparent(true);

        // hiddenPane.setTriggerDistance(DEFAULT_TRIGGER_DISTANCE);
        plotArea.triggerDistanceProperty().bindBidirectional(menuPane.triggerDistanceProperty());
        plotArea.setAnimationDelay(Duration.millis(500));
        // hiddenPane.setMouseTransparent(true);
        plotArea.setPickOnBounds(false);

        // alt: canvas resize (default JavaFX Canvas does not automatically
        // resize to pref width/height according to parent constraints
        // canvas.widthProperty().bind(stackPane.widthProperty());
        // canvas.heightProperty().bind(stackPane.heightProperty());
        getCanvasForeground().setManaged(false);
        final ChangeListener<Number> canvasSizeChangeListener = (ch, o, n) -> {
            final double width = getCanvas().getWidth();
            final double height = getCanvas().getHeight();

            if (getCanvasForeground().getWidth() != width || getCanvasForeground().getHeight() != height) {
                // workaround needed so that pane within pane does not trigger
                // recursions w.r.t. repainting
                getCanvasForeground().resize(width, height);
            }
        };
        canvas.widthProperty().addListener(canvasSizeChangeListener);
        canvas.heightProperty().addListener(canvasSizeChangeListener);

        getCanvasForeground().setMouseTransparent(true);
        getCanvas().toFront();
        getCanvasForeground().toFront();
        pluginsArea.toFront();

        plotArea.getStyleClass().setAll("plot-content");

        plotBackground.getStyleClass().setAll("chart-plot-background");

        if (!canvas.isCache()) {
            canvas.setCache(true);
            canvas.setCacheHint(CacheHint.QUALITY);
        }

        canvas.setStyle("-fx-background-color: rgba(200, 250, 200, 0.5);");

        // add plugin handling and listeners
        getPlugins().addListener(pluginsChangedListener);

        // add default chart content ie. ToolBar and Legend
        // can be repositioned via setToolBarSide(...) and setLegendSide(...)
        titleLabel.setAlignment(Pos.CENTER);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        VBox.setVgrow(titleLabel, Priority.ALWAYS);
        titleLabel.focusTraversableProperty().bind(Platform.accessibilityActiveProperty());

        // register listener in tool bar FlowPane
        toolBar.registerListener();
        menuPane.setTop(getToolBar());

        getTitleLegendPane().addSide(Side.TOP, titleLabel);

        legendVisibleProperty().addListener((ch, old, visible) -> {
            if (getLegend() == null) {
                return;
            }
            getLegend().getNode().setVisible(visible);
            getLegend().getNode().setManaged(visible);
        });

        // set CSS stuff
        titleLabel.getStyleClass().add("chart-title");
        getStyleClass().add("chart");
        axesAndCanvasPane.getStyleClass().add("chart-content");
    }

    @Override
    public BitState getBitState() {
        return state;
    }

    @Override
    public String getUserAgentStylesheet() {
        return CHART_CSS;
    }

    /**
     * Play a animation involving the given keyframes. On every frame of the animation the chart will be relayed out
     *
     * @param keyFrames Array of KeyFrames to play
     */
    public void animate(final KeyFrame... keyFrames) {
        animator.animate(keyFrames);
    }

    public final BooleanProperty animatedProperty() {
        return animated;
    }

    /**
     * @return datasets attached to the chart and datasets attached to all renderers
     */
    public ObservableList<DataSet> getAllDatasets() {
        if (getRenderers() == null) {
            return allDataSets;
        }

        allDataSets.clear();
        allDataSets.addAll(getDatasets());
        getRenderers().stream().filter(renderer -> !(renderer instanceof LabelledMarkerRenderer)).forEach(renderer -> allDataSets.addAll(renderer.getDatasets()));

        return allDataSets;
    }

    public ObservableList<Axis> getAxes() {
        return axesList;
    }

    public ChartPane getAxesAndCanvasPane() {
        return axesAndCanvasPane;
    }

    /**
     * @return the actual canvas the data is being drawn upon
     */
    public final Canvas getCanvas() {
        return canvas;
    }

    public final Pane getCanvasForeground() {
        return canvasForeground;
    }

    /**
     * @return datasets attached to the chart and drawn by all renderers
     */
    public ObservableList<DataSet> getDatasets() {
        return datasets;
    }

    public Axis getFirstAxis(final Orientation orientation) {
        for (final Axis axis : getAxes()) {
            if (axis.getSide() == null) {
                continue;
            }
            switch (orientation) {
            case VERTICAL:
                if (axis.getSide().isVertical()) {
                    return axis;
                }
                break;
            case HORIZONTAL:
            default:
                if (axis.getSide().isHorizontal()) {
                    return axis;
                }
                break;
            }
        }
        // Add default axis if no suitable axis is available
        switch (orientation) {
        case HORIZONTAL:
            DefaultNumericAxis newXAxis = new DefaultNumericAxis("x-Axis");
            newXAxis.setSide(Side.BOTTOM);
            newXAxis.setDimIndex(DataSet.DIM_X);
            getAxes().add(newXAxis);
            return newXAxis;
        case VERTICAL:
        default:
            DefaultNumericAxis newYAxis = new DefaultNumericAxis("y-Axis");
            newYAxis.setSide(Side.LEFT);
            newYAxis.setDimIndex(DataSet.DIM_Y);
            getAxes().add(newYAxis);
            return newYAxis;
        }
    }

    public final Legend getLegend() {
        return legend.getValue();
    }

    public final Side getLegendSide() {
        return legendSide.get();
    }

    public final ChartPane getMeasurementPane() {
        return measurementPane;
    }

    public final HiddenSidesPane getPlotArea() {
        return plotArea;
    }

    public final HiddenSidesPane getMenuPane() {
        return menuPane;
    }

    public final Pane getPlotBackground() {
        return plotBackground;
    }

    public final Pane getPlotForeground() {
        return plotForeGround;
    }

    /**
     * Returns a list of plugins added to this chart pane.
     *
     * @return a modifiable list of plugins
     */
    public final ObservableList<ChartPlugin> getPlugins() {
        return plugins;
    }

    /**
     * @return observable list of associated chart renderers
     */
    public ObservableList<Renderer> getRenderers() {
        return renderers;
    }

    public final String getTitle() {
        return title.get();
    }

    public final ChartPane getTitleLegendPane() {
        return titleLegendPane;
    }

    public final Side getTitleSide() {
        return titleSide.get();
    }

    public final FlowPane getToolBar() {
        return toolBar;
    }

    public final ObjectProperty<Side> getToolBarSideProperty() {
        return toolBarSide;
    }

    public final Side getToolBarSide() {
        return toolBarSideProperty().get();
    }

    /**
     * Indicates whether data changes will be animated or not.
     *
     * @return true if data changes will be animated and false otherwise.
     */
    public final boolean isAnimated() {
        return animated.get();
    }

    public final boolean isLegendVisible() {
        return legendVisible.getValue();
    }

    /**
     * @return true: if chart is being visible in Scene/Window
     */
    public boolean isShowing() {
        return showing.get();
    }

    public boolean isToolBarPinned() {
        return toolBarPinned.get();
    }

    protected void runPreLayout() {
        forEachDataSet(ds -> lockedDataSets.add(ds.lock().readLock()));

        if (state.isDirty(ChartBits.ChartLegend, ChartBits.ChartDataSets, ChartBits.ChartRenderers)) {
            updateLegend(getDatasets(), getRenderers());
        }

        final long start = ProcessingProfiler.getTimeStamp();
        updateAxisRange(); // Update data ranges etc. to trigger anything that might need a layout
        ProcessingProfiler.getTimeDiff(start, "updateAxisRange()");

        for (Renderer renderer : renderers) {
            renderer.runPreLayout();
        }

        for (ChartPlugin plugin : plugins) {
            plugin.runPreLayout();
        }

    }

    private List<DataSet> lockedDataSets = new ArrayList<>();

    @Override
    public void layoutChildren() {
        // Size all nodes to full size. Account for margin and border insets.
        final double x = snappedLeftInset();
        final double y = snappedTopInset();
        final double w = snapSizeX(getWidth()) - x - snappedRightInset();
        final double h = snapSizeY(getHeight()) - y - snappedBottomInset();
        for (Node child : getChildren()) {
            child.resizeRelocate(x, y, w, h);
        }

        // request re-layout of plugins
        if(layoutHooks.hasRunPreLayout()) {
            // datasets are locked, so we can add plugins
            layoutPluginsChildren();
        } else {
            // There are some rare corner cases, e.g., computing
            // the pref size of the scene, that call for a layout
            // without calling the hooks. The plugins may rely
            // on datasets being locked, so we try again next
            // pulse to be safe.
            Platform.runLater(this::requestLayout);
        }

        // Make sure things will get redrawn
        fireInvalidated(ChartBits.ChartCanvas);
    }

    protected void runPostLayout() {
        // Update the actual Canvas content
        final long start = ProcessingProfiler.getTimeStamp();
        for (Axis axis : axesList) {
            axis.drawAxis();
        }
        redrawCanvas();
        for (var renderer : getRenderers()) {
            if (renderer instanceof EventSource) {
                ((EventSource) renderer).getBitState().clear();
            }
        }
        for (var plugin : plugins) {
            plugin.runPostLayout();
            if (plugin instanceof EventSource) {
                ((EventSource) plugin).getBitState().clear();
            }
        }
        dataSetState.clear();
        state.clear();
        for (var ds : lockedDataSets) {
            ds.getBitState().clear(); // technically a 'write'
            ds.lock().readUnLock();
        }
        lockedDataSets.clear();

        // TODO: plugins etc., do locking
        ProcessingProfiler.getTimeDiff(start, "updateCanvas()");
    }

    private void forEachDataSet(Consumer<DataSet> action) {
        for (DataSet dataset : datasets) {
            action.accept(dataset);
        }
        for (Renderer renderer : renderers) {
            for (DataSet dataset : renderer.getDatasets()) {
                action.accept(dataset);
            }
        }
    }

    public final ObjectProperty<Legend> legendProperty() {
        return legend;
    }

    public final ObjectProperty<Side> legendSideProperty() {
        return legendSide;
    }

    public final BooleanProperty legendVisibleProperty() {
        return legendVisible;
    }

    public final void setAnimated(final boolean value) {
        animated.set(value);
    }

    public final void setLegend(final Legend value) {
        legend.set(value);
    }

    public final void setLegendSide(final Side value) {
        legendSide.set(value);
    }

    public final void setLegendVisible(final boolean value) {
        legendVisible.set(value);
    }

    public final void setTitle(final String value) {
        title.set(value);
    }

    public final void setTitleSide(final Side value) {
        titleSide.set(value);
    }

    public final void setTitlePaint(final Paint paint) {
        titleLabel.setTextFill(paint);
    }

    public Chart setToolBarPinned(boolean value) {
        toolBarPinned.set(value);
        return this;
    }

    public final void setToolBarSide(final Side value) {
        toolBarSide.set(value);
    }

    /**
     * @return property indicating if chart is actively visible in Scene/Window
     */
    public ReadOnlyBooleanProperty showingProperty() {
        return showing;
    }

    public final StringProperty titleProperty() {
        return title;
    }

    public final ObjectProperty<Side> titleSideProperty() {
        return titleSide;
    }

    public BooleanProperty toolBarPinnedProperty() {
        return toolBarPinned;
    }

    public final ObjectProperty<Side> toolBarSideProperty() {
        return toolBarSide;
    }

    // -------------- CONSTRUCTOR
    // --------------------------------------------------------------------------------------

    /**
     * Translates point from chart pane coordinates to the plot area coordinates.
     *
     * @param xCoord the x coordinate within XYChartPane coordinates system
     * @param yCoord the y coordinate within XYChartPane coordinates system
     * @return point in plot area coordinates
     */
    public final Point2D toPlotArea(final double xCoord, final double yCoord) {
        final Bounds plotAreaBounds = getCanvas().getBoundsInParent();
        return new Point2D(xCoord - plotAreaBounds.getMinX(), yCoord - plotAreaBounds.getMinY());
    }

    // -------------- METHODS
    // ------------------------------------------------------------------------------------------

    /**
     * update axes ranges (if necessary). This is supposed to be implemented in derived classes
     */
    public abstract void updateAxisRange();

    /**
     * Play the given animation on every frame of the animation the chart will be relayed out until the animation
     * finishes. So to add a animation to a chart, create a animation on data model, during layoutChartContent() map
     * data model to nodes then call this method with the animation.
     *
     * @param animation The animation to play
     */
    protected void animate(final Animation animation) {
        animator.animate(animation);
    }

    /**
     * add Chart specific axis handling (ie. placement around charts, add new DefaultNumericAxis if one is missing,
     * etc.)
     *
     * @param change the new axis change that is being added
     */
    protected abstract void axesChanged(final ListChangeListener.Change<? extends Axis> change);

    /**
     * add Chart specific axis handling (ie. placement around charts, add new DefaultNumericAxis if one is missing,
     * etc.)
     *
     * @param change the new axis change that is being added
     */
    protected void axesChangedLocal(final ListChangeListener.Change<? extends Axis> change) {
        while (change.next()) {
            for (Axis axis : change.getRemoved()) {
                // remove axis invalidation listener
                AssertUtils.notNull("to be removed axis is null", axis);
                axis.removeListener(state);
                removeAxisFromChildren(axis); // TODO: don't remove if it is contained in getAxes()
            }
            for (final Axis axis : change.getAddedSubList()) {
                // check if axis is associated with an existing renderer,
                // if yes -> throw an exception
                AssertUtils.notNull("to be added axis is null", axis);
                axis.addListener(state);
                addAxisToChildren(axis);
            }
        }
        fireInvalidated(ChartBits.ChartLayout, ChartBits.ChartAxes);
    }

    private boolean addAxisToChildren(Axis axis) {
        final Side side = axis.getSide();
        if (side == null) {
            throw new InvalidParameterException("axis '" + axis.getName() + "' has 'null' as side being set");
        }
        var children = getAxesAndCanvasPane().getChildren();
        if (axis instanceof Node && !children.contains(axis)) {
            getAxesAndCanvasPane().addSide(side, (Node) axis);
            return true;
        }
        return false;
    }

    private boolean removeAxisFromChildren(Axis axis) {
        var children = getAxesAndCanvasPane().getChildren();
        if (axis instanceof Node) {
            children.remove((Node) axis);
            return true;
        }
        return false;
    }

    protected void datasetsChanged(final ListChangeListener.Change<? extends DataSet> change) {
        FXUtils.assertJavaFxThread();
        while (change.next()) {
            for (final DataSet set : change.getRemoved()) {
                set.removeListener(dataSetState);
            }
            for (final DataSet set : change.getAddedSubList()) {
                set.addListener(dataSetState);
            }
        }
        fireInvalidated(ChartBits.ChartLayout, ChartBits.ChartDataSets, ChartBits.ChartLegend);
    }

    /**
     * @return unmodifiable list of the controls css styleable properties
     * @since JavaFX 8.0
     */
    @Deprecated // A remnant of extending Control. Do we need it?
    protected List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return Chart.getClassCssMetaData();
    }

    protected void layoutPluginsChildren() {
        plugins.forEach(ChartPlugin::layoutChildren);
    }

    protected void pluginAdded(final ChartPlugin plugin) {
        plugin.setChart(Chart.this);
        final Group group = Chart.createChildGroup();
        Bindings.bindContent(group.getChildren(), plugin.getChartChildren());
        pluginGroups.put(plugin, group);
    }

    // -------------- STYLESHEET HANDLING
    // ------------------------------------------------------------------------------

    protected void pluginRemoved(final ChartPlugin plugin) {
        plugin.setChart(null);
        final Group group = pluginGroups.remove(plugin);
        Bindings.unbindContent(group, plugin.getChartChildren());
        group.getChildren().clear();
        pluginsArea.getChildren().remove(group);
    }

    protected void pluginsChanged(final ListChangeListener.Change<? extends ChartPlugin> change) {
        while (change.next()) {
            change.getRemoved().forEach(this::pluginRemoved);
            change.getAddedSubList().forEach(this::pluginAdded);
        }
        updatePluginsArea();
    }

    /**
     * (re-)draw canvas (if necessary). This is supposed to be implemented in derived classes
     */
    protected abstract void redrawCanvas();

    // -------------- LISTENER HANDLING
    // ------------------------------------------------------------------------------

    protected void rendererChanged(final ListChangeListener.Change<? extends Renderer> change) {
        FXUtils.assertJavaFxThread();
        while (change.next()) {
            // TODO: how to work with renderer axes that are not in the SceneGraph? The length would never get set.

            // handle added renderer
            for (Renderer renderer : change.getAddedSubList()) {
                for (DataSet dataset : renderer.getDatasets()) {
                    dataset.addListener(dataSetState);
                }
                renderer.getDatasets().addListener(datasetChangeListener);
            }

            // handle removed renderer
            for (Renderer renderer : change.getRemoved()) {
                for (DataSet dataset : renderer.getDatasets()) {
                    dataset.removeListener(dataSetState);
                }
                renderer.getDatasets().removeListener(datasetChangeListener);
            }

        }
        // reset change to allow derived classes to add additional listeners to renderer changes
        change.reset();
        fireInvalidated(ChartBits.ChartLayout, ChartBits.ChartRenderers, ChartBits.ChartLegend);

    }

    /**
     * This is used to check if any given animation should run. It returns true if animation is enabled and the node is
     * visible and in a scene.
     *
     * @return true if should animate
     */
    protected final boolean shouldAnimate() {
        return isAnimated() && getScene() != null;
    }

    protected void updateLegend(final List<DataSet> dataSets, final List<Renderer> renderers) {
        final Legend legend = getLegend();
        if (legend == null) {
            return;
        }
        legend.updateLegend(dataSets, renderers);
    }

    protected void updatePluginsArea() {
        var pluginChildren = plugins.stream().map(pluginGroups::get).collect(Collectors.toList());
        pluginsArea.getChildren().setAll(pluginChildren);
        fireInvalidated(ChartBits.ChartPlugins);
    }

    /**
     * @return The CssMetaData associated with this class, which may include the CssMetaData of its super classes.
     * @since JavaFX 8.0
     */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return CSS.getCssMetaData();
    }

    protected static Group createChildGroup() {
        final Group group = new Group();
        group.setManaged(false);
        group.setAutoSizeChildren(false);
        group.relocate(0, 0);
        return group;
    }

    protected static class ChartHBox extends HBox {
        public ChartHBox(Node... nodes) {
            super();
            setAlignment(Pos.CENTER);
            setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            getChildren().addAll(nodes);
            visibleProperty().addListener((obs, o, n) -> getChildren().forEach(node -> node.setVisible(n)));
        }

        public ChartHBox(final boolean fill) {
            this();
            setFillHeight(fill);
        }
    }

    protected static class ChartVBox extends VBox {
        public ChartVBox(Node... nodes) {
            super();
            setAlignment(Pos.CENTER);
            setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            getChildren().addAll(nodes);
            visibleProperty().addListener((obs, o, n) -> getChildren().forEach(node -> node.setVisible(n)));
        }

        public ChartVBox(final boolean fill) {
            this();
            setFillWidth(fill);
        }
    }
}

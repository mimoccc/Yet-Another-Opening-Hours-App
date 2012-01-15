package org.yaoha;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.osmdroid.ResourceProxy;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.BoundingBoxE6;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapController.AnimationType;
import org.osmdroid.views.MapView;
import org.osmdroid.views.MapView.LayoutParams;
import org.osmdroid.views.overlay.ItemizedOverlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.yaoha.YaohaMapListener.Direction;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

public class NodesOverlay extends ItemizedOverlay<OverlayItem> implements NodeReceiverInterface<OsmNode> {
    HashMap<Integer, OsmNode> nodes;
    Iterator<Integer> iter;
    Activity act;
    MapView mapView;
    long last_toast_started;
    OsmNode last_node;
    BalloonOverlayView<OverlayItem> balloonView;
    int viewOffset;
    private View clickRegion;
    NodesQueryInterface<Integer, OsmNode> iQuery;
    String[] search_terms;
    BoundingBoxE6 old_box;
    
    public NodesOverlay(Drawable pDefaultMarker, ResourceProxy pResourceProxy, Activity act, MapView mapview, NodesQueryInterface<Integer, OsmNode> iQuery, String search_term) {
        super(pDefaultMarker, pResourceProxy);
        this.act = act;
        this.mapView = mapview;
        this.nodes = new HashMap<Integer, OsmNode>();
        this.iQuery = iQuery;
        this.iQuery.addListener(this);
        this.search_terms = search_term.toLowerCase().split(" ");
    }

    void getNodes(BoundingBoxE6 bb) {
        Map<Integer, OsmNode> tmp_nodes = new HashMap<Integer, OsmNode>();
        
        Map<Direction, Boolean> bbox_stats = YaohaMapListener.getBoundingBoxMove(old_box, bb);
        if (bbox_stats != null) {
            // moved north
            if (bbox_stats.get(YaohaMapListener.Direction.NORTH))
                tmp_nodes.putAll(iQuery.getNodesFromMapExtract(bb.getLatNorthE6(), old_box.getLatNorthE6(), old_box.getLonWestE6(), old_box.getLonEastE6(), search_terms));
            // moved south
            if (bbox_stats.get(Direction.SOUTH))
                tmp_nodes.putAll(iQuery.getNodesFromMapExtract(old_box.getLatSouthE6(), bb.getLatSouthE6(), old_box.getLonWestE6(), old_box.getLonEastE6(), search_terms));
            // with latitude of bbox to get the entire height
            // moved east
            if (bbox_stats.get(Direction.EAST))
                tmp_nodes.putAll(iQuery.getNodesFromMapExtract(bb.getLatNorthE6(), bb.getLatSouthE6(), bb.getLonEastE6(), old_box.getLonEastE6(), search_terms));
            // moved west
            if (bbox_stats.get(Direction.WEST))
                tmp_nodes.putAll(iQuery.getNodesFromMapExtract(bb.getLatNorthE6(), bb.getLatSouthE6(), old_box.getLonWestE6(), bb.getLonWestE6(), search_terms));
        } else
            tmp_nodes = iQuery.getNodesFromMapExtract(bb.getLonWestE6(), bb.getLatNorthE6(), bb.getLonEastE6(), bb.getLatSouthE6(), search_terms);
        
        // TODO do search with the database
        for (Integer key : tmp_nodes.keySet()) {
            OsmNode node = tmp_nodes.get(key);
            if (nodeMatchesSearchTerms(node))
                nodes.put(key, node);
        }
        iter = nodes.keySet().iterator();
        populate();
    }
    
    @Override
    public boolean onSnapToItem(int x, int y, Point snapPoint, MapView mapView) {
        return false;
    }

    @Override
    protected OverlayItem createItem(int i) {
        Log.d(this.getClass().getSimpleName(), "drawing one node");
        if (!iter.hasNext())
            iter = nodes.keySet().iterator();
        
        OsmNode node = nodes.get(iter.next());
        return createItemFromNode(node);
    }
    
    private OverlayItem createItemFromNode(OsmNode node) {
        GeoPoint geop = new GeoPoint(node.getLatitudeE6(), node.getLongitudeE6());
        OverlayItem oi = new OverlayItem(node.getName(), node.getOpening_hours(), geop);
        switch (node.isOpenNow()) {
        case CLOSED:
            oi.setMarker(act.getResources().getDrawable(R.drawable.closed));
            break;
        case OPEN:
            oi.setMarker(act.getResources().getDrawable(R.drawable.open));
            break;
        case MAYBE:
            oi.setMarker(act.getResources().getDrawable(R.drawable.maybe));
            break;
        case UNSET:
            oi.setMarker(act.getResources().getDrawable(R.drawable.dontknow));
            break;
        }
        return oi;
    }

    @Override
    public int size() {
        Log.d(this.getClass().getSimpleName(), "returning size of " + nodes.size() + " nodes");
        return nodes.size();
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event, MapView mapView) {
        boolean ret_val = super.onTouchEvent(event, mapView);
        if (event.getAction() != MotionEvent.ACTION_DOWN)
            return ret_val;
        
        float x = event.getX();
        float y = event.getY();
        Log.d(this.getClass().getSimpleName(), "touch event at (" + x + ", " + y + ")");
        
        Drawable image_size = act.getResources().getDrawable(R.drawable.closed);
        int offset_x = image_size.getMinimumWidth();
        int offset_y = image_size.getMinimumHeight();
        
        IGeoPoint event_on_map_minus_offset = mapView.getProjection().fromPixels(x-offset_x, y-0*offset_y);
        IGeoPoint event_on_map_plus_offset = mapView.getProjection().fromPixels(x+offset_x, y+2*offset_y);
        
        Log.d(this.getClass().getSimpleName(), "event on map is from " + event_on_map_minus_offset + " to " + event_on_map_plus_offset);
        Rect rect_around_event = new Rect(event_on_map_minus_offset.getLongitudeE6(),
                event_on_map_plus_offset.getLatitudeE6(),
                event_on_map_plus_offset.getLongitudeE6(),
                event_on_map_minus_offset.getLatitudeE6());
         
        OsmNode n = null;
        List<Integer> nodesToRemove = new LinkedList<Integer>();
        BoundingBoxE6 bb = mapView.getBoundingBox();
        for (Integer index : nodes.keySet()) {
            OsmNode tmp_node = nodes.get(index);
            if (rect_around_event.contains(tmp_node.getLongitudeE6(), tmp_node.getLatitudeE6())) {
                n = tmp_node;
                break;
            }
            if (!bb.contains(tmp_node.getLatitudeE6(), tmp_node.getLongitudeE6()))
                nodesToRemove.add(index);
        }
        
        for (Integer i : nodesToRemove)
            nodes.remove(i);
        if (nodesToRemove.size() != 0)
            this.iter = nodes.keySet().iterator();
        
        if (n != null) {
            last_toast_started = System.currentTimeMillis();
            last_node = n;
            createAndDisplayBalloonOverlay(n);
        }
        
        return ret_val;
    }

    @Override
    public void put(OsmNode value) {
        // check if node is displayable
        BoundingBoxE6 bb = mapView.getBoundingBox();
        if (!bb.contains(value.getLatitudeE6(), value.getLongitudeE6()))
            return;
        
        if (!nodeMatchesSearchTerms(value))
            return;
        
        // this must be a node we are interested in
        this.nodes.put(value.getID(), value);
        this.iter = nodes.keySet().iterator();
        populate();
        mapView.postInvalidate();
    }
    
    boolean nodeMatchesSearchTerms(OsmNode node) {
        // check if there was no search or a search term matches
        boolean search_matches = search_terms.length == 0;
        for (String key : node.getKeys()) {
            String tag_value = node.getAttribute(key);
            search_matches |= checkSearchStringForTagAndValue(key.toLowerCase(), tag_value.toLowerCase());
        }
        return search_matches;
    }

    boolean checkSearchStringForTagAndValue(String tag, String value) {
        for (String seach_term : search_terms) {
            if (tag.contains(seach_term) || value.contains(seach_term))
                return true;
        }
        return false;
    }

    /**
     * Set the horizontal distance between the marker and the bottom of the information
     * balloon. The default is 0 which works well for center bounded markers. If your
     * marker is center-bottom bounded, call this before adding overlay items to ensure
     * the balloon hovers exactly above the marker. 
     * 
     * @param pixels - The padding between the center point and the bottom of the
     * information balloon.
     */
    public void setBalloonBottomOffset(int pixels) {
        viewOffset = pixels;
    }
    public int getBalloonBottomOffset() {
        return viewOffset;
    }
    
    /**
     * Creates the balloon view. Override to create a sub-classed view that
     * can populate additional sub-views.
     */
    protected BalloonOverlayView<OverlayItem> createBalloonOverlayView() {
        return new BalloonOverlayView<OverlayItem>(getMapView().getContext(), getBalloonBottomOffset());
    }
    
    /**
     * Expose map view to subclasses.
     * Helps with creation of balloon views. 
     */
    protected MapView getMapView() {
        return mapView;
    }
    
    /**
     * Sets the onTouchListener for the balloon being displayed, calling the
     * overridden {@link #onBalloonTap} method.
     */
    private OnTouchListener createBalloonTouchListener() {
        return new OnTouchListener() {
            
            float startX;
            float startY;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                
                View l =  ((View) v.getParent()).findViewById(R.id.balloon_main_layout);
                Drawable d = l.getBackground();
                
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    int[] states = {android.R.attr.state_pressed};
                    if (d.setState(states)) {
                        d.invalidateSelf();
                    }
                    startX = event.getX();
                    startY = event.getY();
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    int newStates[] = {};
                    if (d.setState(newStates)) {
                        d.invalidateSelf();
                    }
                    if (Math.abs(startX - event.getX()) < 40 && 
                            Math.abs(startY - event.getY()) < 40 ) {
                        // call overridden method
                        onBalloonTap(last_node);
                    }
                    return true;
                } else {
                    return false;
                }
                
            }
        };
    }
    
    protected void onBalloonTap(OsmNode last_node2) {
     // start activity displaying more information, which can be editable
        Intent intent = new Intent("displayStuff");
        intent = new Intent(act, NodeActivity.class);
        
        for (String key : last_node2.getKeys()) {
            intent.putExtra(key, last_node2.getAttribute(key));
        }
        act.startActivity(intent);
    }
    
    /**
     * Creates and displays the balloon overlay by recycling the current 
     * balloon or by inflating it from xml. 
     * @return true if the balloon was recycled false otherwise 
     */
    private boolean createAndDisplayBalloonOverlay(OsmNode node){
        boolean isRecycled;
        if (balloonView == null) {
            balloonView = createBalloonOverlayView();
            clickRegion = (View) balloonView.findViewById(R.id.balloon_inner_layout);
            clickRegion.setOnTouchListener(createBalloonTouchListener());
            isRecycled = false;
        } else {
            isRecycled = true;
        }
    
        balloonView.setVisibility(View.GONE);
        
        OverlayItem item = createItemFromNode(last_node);
        if (balloonView != null && last_node != null)
            balloonView.setData(item);
        
        GeoPoint point = item.getPoint();
        
        MapView.LayoutParams params = new MapView.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                point,
                MapView.LayoutParams.BOTTOM_CENTER, 0, 0);
        
        balloonView.setVisibility(View.VISIBLE);
        mapView.getController().animateTo(node.getLatitudeE6(), node.getLongitudeE6(), AnimationType.EXPONENTIALDECELERATING);
        
        if (isRecycled) {
            balloonView.setLayoutParams(params);
        } else {
            mapView.addView(balloonView, params);
        }
        
        return isRecycled;
    }
}

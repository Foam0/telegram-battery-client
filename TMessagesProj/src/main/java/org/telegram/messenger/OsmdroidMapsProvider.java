package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.location.Location;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.util.Consumer;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.ArrayList;
import java.util.List;

public class OsmdroidMapsProvider implements IMapsProvider {

    @Override
    public void initializeMaps(Context context) {
        Configuration.getInstance().setUserAgentValue(context.getPackageName());
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        return new OsmdroidMapView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new OsmdroidMarkerOptions();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new OsmdroidCircleOptions();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new OsmdroidLatLngBoundsBuilder();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return new OsmdroidCameraUpdate(latLng, -1);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return new OsmdroidCameraUpdate(latLng, zoom);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new OsmdroidCameraUpdateBounds((OsmdroidLatLngBounds) bounds, padding);
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return null; // osmdroid doesn't support map styles
    }

    @Override
    public String getMapsAppPackageName() {
        return "net.osmand";
    }

    @Override
    public int getInstallMapsString() {
        return 0; // osmdroid is bundled, no install needed
    }

    // --- Inner classes ---

    public static class OsmdroidCameraUpdate implements ICameraUpdate {
        final LatLng latLng;
        final float zoom;

        OsmdroidCameraUpdate(LatLng latLng, float zoom) {
            this.latLng = latLng;
            this.zoom = zoom;
        }
    }

    public static class OsmdroidCameraUpdateBounds implements ICameraUpdate {
        final OsmdroidLatLngBounds bounds;
        final int padding;

        OsmdroidCameraUpdateBounds(OsmdroidLatLngBounds bounds, int padding) {
            this.bounds = bounds;
            this.padding = padding;
        }
    }

    public static class OsmdroidMapImpl implements IMap {
        private MapView mapView;
        private Runnable onCameraIdleListener;
        private Runnable onCameraMoveListener;
        private OnCameraMoveStartedListener onCameraMoveStartedListener;
        private Consumer<Location> onMyLocationChangeListener;
        private OnMarkerClickListener onMarkerClickListener;

        OsmdroidMapImpl(MapView mapView) {
            this.mapView = mapView;
            mapView.addMapListener(new MapListener() {
                @Override
                public boolean onScroll(ScrollEvent event) {
                    if (onCameraMoveListener != null) onCameraMoveListener.run();
                    if (onCameraMoveStartedListener != null)
                        onCameraMoveStartedListener.onCameraMoveStarted(OnCameraMoveStartedListener.REASON_GESTURE);
                    return false;
                }
                @Override
                public boolean onZoom(ZoomEvent event) {
                    if (onCameraMoveListener != null) onCameraMoveListener.run();
                    return false;
                }
            });
        }

        @Override
        public void setMapType(int mapType) {
            switch (mapType) {
                case MAP_TYPE_SATELLITE:
                    mapView.setTileSource(TileSourceFactory.USGS_SAT);
                    break;
                case MAP_TYPE_HYBRID:
                    mapView.setTileSource(TileSourceFactory.USGS_TOPO);
                    break;
                default:
                case MAP_TYPE_NORMAL:
                    mapView.setTileSource(TileSourceFactory.MAPNIK);
                    break;
            }
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            animateCamera(update, null);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            applyCamera(update);
            if (callback != null) callback.onFinish();
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            applyCamera(update);
            if (callback != null) callback.onFinish();
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            applyCamera(update);
        }

        private void applyCamera(ICameraUpdate update) {
            if (update instanceof OsmdroidCameraUpdate) {
                OsmdroidCameraUpdate u = (OsmdroidCameraUpdate) update;
                mapView.getController().setCenter(new GeoPoint(u.latLng.latitude, u.latLng.longitude));
                if (u.zoom > 0) mapView.getController().setZoom((double) u.zoom);
            } else if (update instanceof OsmdroidCameraUpdateBounds) {
                OsmdroidCameraUpdateBounds u = (OsmdroidCameraUpdateBounds) update;
                if (u.bounds != null) {
                    mapView.zoomToBoundingBox(u.bounds.boundingBox, false, u.padding);
                }
            }
            if (onCameraIdleListener != null) {
                mapView.post(onCameraIdleListener);
            }
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) mapView.getMaxZoomLevel();
        }

        @Override
        public float getMinZoomLevel() {
            return (float) mapView.getMinZoomLevel();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void setMyLocationEnabled(boolean enabled) {
            // handled by location overlays if needed
        }

        @Override
        public IUISettings getUiSettings() {
            return new IUISettings() {
                @Override
                public void setZoomControlsEnabled(boolean enabled) {
                    mapView.setBuiltInZoomControls(enabled);
                }
                @Override
                public void setMyLocationButtonEnabled(boolean enabled) {}
                @Override
                public void setCompassEnabled(boolean enabled) {}
            };
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            this.onCameraIdleListener = callback;
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener listener) {
            this.onCameraMoveStartedListener = listener;
        }

        @Override
        public CameraPosition getCameraPosition() {
            IGeoPoint center = mapView.getMapCenter();
            return new CameraPosition(new LatLng(center.getLatitude(), center.getLongitude()), (float) mapView.getZoomLevelDouble());
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            if (callback != null) mapView.post(callback);
        }

        @Override
        public IProjection getProjection() {
            return latLng -> {
                org.osmdroid.views.Projection proj = mapView.getProjection();
                android.graphics.Point pt = proj.toPixels(new GeoPoint(latLng.latitude, latLng.longitude), null);
                return pt;
            };
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {}

        @Override
        public void setMapStyle(IMapStyleOptions style) {}

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            OsmdroidMarkerOptions opts = (OsmdroidMarkerOptions) markerOptions;
            Marker marker = new Marker(mapView);
            if (opts.position != null) {
                marker.setPosition(new GeoPoint(opts.position.latitude, opts.position.longitude));
            }
            if (opts.icon != null) {
                marker.setIcon(new android.graphics.drawable.BitmapDrawable(mapView.getResources(), opts.icon));
            }
            if (opts.title != null) marker.setTitle(opts.title);
            if (opts.snippet != null) marker.setSnippet(opts.snippet);
            marker.setAnchor(opts.anchorU, opts.anchorV);
            marker.setFlat(opts.flat);
            marker.setRotation(opts.rotation);
            marker.setInfoWindow(null);
            if (onMarkerClickListener != null) {
                marker.setOnMarkerClickListener((m, mv) -> {
                    return onMarkerClickListener.onClick(wrapMarker(m));
                });
            }
            mapView.getOverlays().add(marker);
            mapView.invalidate();
            return wrapMarker(marker);
        }

        private IMarker wrapMarker(Marker marker) {
            return new IMarker() {
                Object tag;
                @Override public Object getTag() { return tag; }
                @Override public void setTag(Object t) { tag = t; }
                @Override public LatLng getPosition() {
                    GeoPoint pt = marker.getPosition();
                    return new LatLng(pt.getLatitude(), pt.getLongitude());
                }
                @Override public void setPosition(LatLng latLng) {
                    marker.setPosition(new GeoPoint(latLng.latitude, latLng.longitude));
                    mapView.invalidate();
                }
                @Override public void setRotation(int rotation) {
                    marker.setRotation(rotation);
                    mapView.invalidate();
                }
                @Override public void setIcon(Bitmap bitmap) {
                    marker.setIcon(new android.graphics.drawable.BitmapDrawable(mapView.getResources(), bitmap));
                    mapView.invalidate();
                }
                @Override public void setIcon(int resId) {
                    marker.setIcon(mapView.getResources().getDrawable(resId));
                    mapView.invalidate();
                }
                @Override public void remove() {
                    mapView.getOverlays().remove(marker);
                    mapView.invalidate();
                }
            };
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            OsmdroidCircleOptions opts = (OsmdroidCircleOptions) circleOptions;
            Polygon circle = new Polygon(mapView);
            circle.setFillColor(opts.fillColor);
            circle.setStrokeColor(opts.strokeColor);
            circle.setStrokeWidth(opts.strokeWidth);
            if (opts.center != null) {
                List<GeoPoint> points = buildCirclePoints(opts.center, opts.radius);
                circle.setPoints(points);
            }
            mapView.getOverlays().add(circle);
            mapView.invalidate();
            final double[] radiusHolder = {opts.radius};
            final LatLng[] centerHolder = {opts.center};
            return new ICircle() {
                @Override public void setStrokeColor(int color) { circle.setStrokeColor(color); mapView.invalidate(); }
                @Override public void setFillColor(int color) { circle.setFillColor(color); mapView.invalidate(); }
                @Override public void setRadius(double r) {
                    radiusHolder[0] = r;
                    if (centerHolder[0] != null) {
                        circle.setPoints(buildCirclePoints(centerHolder[0], r));
                        mapView.invalidate();
                    }
                }
                @Override public double getRadius() { return radiusHolder[0]; }
                @Override public void setCenter(LatLng latLng) {
                    centerHolder[0] = latLng;
                    circle.setPoints(buildCirclePoints(latLng, radiusHolder[0]));
                    mapView.invalidate();
                }
                @Override public void remove() {
                    mapView.getOverlays().remove(circle);
                    mapView.invalidate();
                }
            };
        }

        private List<GeoPoint> buildCirclePoints(LatLng center, double radiusMeters) {
            List<GeoPoint> points = new ArrayList<>();
            int segments = 64;
            for (int i = 0; i < segments; i++) {
                double angle = Math.toRadians(i * 360.0 / segments);
                double dx = radiusMeters * Math.cos(angle);
                double dy = radiusMeters * Math.sin(angle);
                double dLat = dy / 111320.0;
                double dLng = dx / (111320.0 * Math.cos(Math.toRadians(center.latitude)));
                points.add(new GeoPoint(center.latitude + dLat, center.longitude + dLng));
            }
            return points;
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            this.onMyLocationChangeListener = callback;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener listener) {
            this.onMarkerClickListener = listener;
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            this.onCameraMoveListener = callback;
        }
    }

    public static class OsmdroidMapView implements IMapView {
        private MapView mapView;
        private OsmdroidMapImpl mapImpl;

        OsmdroidMapView(Context context) {
            mapView = new MapView(context);
            mapView.setMultiTouchControls(true);
            mapView.setBuiltInZoomControls(false);
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapImpl = new OsmdroidMapImpl(mapView);
        }

        @Override
        public View getView() {
            return mapView;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            mapView.post(() -> callback.accept(mapImpl));
        }

        @Override
        public void onResume() { mapView.onResume(); }

        @Override
        public void onPause() { mapView.onPause(); }

        @Override
        public void onCreate(Bundle savedInstance) {}

        @Override
        public void onDestroy() { mapView.onDetach(); }

        @Override
        public void onLowMemory() {}

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            // wrap the mapView's dispatchTouchEvent
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {}

        @Override
        public void setOnLayoutListener(Runnable callback) {
            mapView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> callback.run());
        }

        @Override
        public GLSurfaceView getGlSurfaceView() {
            return null;
        }
    }

    public static class OsmdroidMarkerOptions implements IMarkerOptions {
        LatLng position;
        Bitmap icon;
        int iconResId;
        String title;
        String snippet;
        float anchorU = Marker.ANCHOR_CENTER;
        float anchorV = Marker.ANCHOR_BOTTOM;
        boolean flat;
        float rotation;

        @Override
        public IMarkerOptions position(LatLng latLng) { this.position = latLng; return this; }
        @Override
        public IMarkerOptions icon(Bitmap bitmap) { this.icon = bitmap; return this; }
        @Override
        public IMarkerOptions icon(int resId) { this.iconResId = resId; return this; }
        @Override
        public IMarkerOptions anchor(float u, float v) { this.anchorU = u; this.anchorV = v; return this; }
        @Override
        public IMarkerOptions title(String title) { this.title = title; return this; }
        @Override
        public IMarkerOptions snippet(String snippet) { this.snippet = snippet; return this; }
        @Override
        public IMarkerOptions flat(boolean flat) { this.flat = flat; return this; }
    }

    public static class OsmdroidCircleOptions implements ICircleOptions {
        LatLng center;
        double radius;
        int strokeColor;
        int fillColor;
        int strokeWidth;

        @Override
        public ICircleOptions center(LatLng center) { this.center = center; return this; }
        @Override
        public ICircleOptions radius(double radius) { this.radius = radius; return this; }
        @Override
        public ICircleOptions strokeColor(int color) { this.strokeColor = color; return this; }
        @Override
        public ICircleOptions fillColor(int color) { this.fillColor = color; return this; }
        @Override
        public ICircleOptions strokePattern(List<PatternItem> pattern) { return this; }
        @Override
        public ICircleOptions strokeWidth(int width) { this.strokeWidth = width; return this; }
    }

    public static class OsmdroidLatLngBoundsBuilder implements ILatLngBoundsBuilder {
        private double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        private double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;

        @Override
        public ILatLngBoundsBuilder include(LatLng latLng) {
            minLat = Math.min(minLat, latLng.latitude);
            maxLat = Math.max(maxLat, latLng.latitude);
            minLng = Math.min(minLng, latLng.longitude);
            maxLng = Math.max(maxLng, latLng.longitude);
            return this;
        }

        @Override
        public ILatLngBounds build() {
            BoundingBox box = new BoundingBox(maxLat, maxLng, minLat, minLng);
            return new OsmdroidLatLngBounds(box);
        }
    }

    public static class OsmdroidLatLngBounds implements ILatLngBounds {
        final BoundingBox boundingBox;

        OsmdroidLatLngBounds(BoundingBox boundingBox) {
            this.boundingBox = boundingBox;
        }

        @Override
        public LatLng getCenter() {
            GeoPoint center = boundingBox.getCenterWithDateLine();
            return new LatLng(center.getLatitude(), center.getLongitude());
        }
    }
}

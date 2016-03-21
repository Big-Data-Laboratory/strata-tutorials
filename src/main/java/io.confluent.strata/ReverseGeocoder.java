package io.confluent.strata;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.index.SpatialIndex;
import com.vividsolutions.jts.index.strtree.STRtree;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.geotools.data.*;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Created by jadler on 3/17/16.
 */
public class ReverseGeocoder implements ValueMapper<GenericRecord, GenericRecord>  {

    SpatialIndex qt = new STRtree();
    String latitudeKey = null;
    String longitudeKey = null;
    String locationNameKey = null;

    public ReverseGeocoder(Collection<File> shapefiles)  {
        super();
        shapefiles.forEach(file -> loadShapeFile(file));
    }

    public ReverseGeocoder(Collection<File> shapefiles, String initialLatitudeKey,
                           String initialLongitudeKey, String initialLocationName)  {
        super();
        latitudeKey = initialLatitudeKey;
        longitudeKey = initialLongitudeKey;
        locationNameKey = initialLocationName;
        shapefiles.forEach(file -> loadShapeFile(file));
    }

    public void loadShapeFile(File shapefile)  {
        try {
            Map<String, Object> shapefileParams = Maps.newHashMap();
            shapefileParams.put("url", shapefile.toURI().toURL());
            DataStore dataStore = DataStoreFinder.getDataStore(shapefileParams);
            String typeName = dataStore.getTypeNames()[0];
            FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
            Filter filter = Filter.INCLUDE;
            FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures(filter);

            try (FeatureIterator<SimpleFeature> features = collection.features()) {
                while (features.hasNext()) {
                    SimpleFeature feature = features.next();
                    GeoInfo geoInfo = GeoInfo.fromSimpleFeature(feature);
                    qt.insert(GeoUtils.getBoundingRectangleAsEnvelope(geoInfo.multiPolygon),
                            geoInfo);
                }
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private GeometryFactory geometryFactory = new GeometryFactory();

    public GeoInfo findGeoInfoForPoint(double x, double y) {
        Coordinate coordinate = new Coordinate(x,y);
        Geometry geometry = geometryFactory.createPoint(coordinate);
        List<GeoInfo> candidates =  qt.query(new Envelope(coordinate));
        System.err.printf("Looking at a list of %d candidates\n", candidates.size());
        for (GeoInfo candidate: candidates) {
            if (candidate.multiPolygon.contains(geometry))
                return candidate;
        }
        return null;
    }

    public List<GeoInfo> findAllGeoInfoForPoint(double x, double y) {
        Coordinate coordinate = new Coordinate(x,y);
        Geometry geometry = geometryFactory.createPoint(coordinate);
        List<GeoInfo> candidates =  qt.query(new Envelope(coordinate));
        System.err.printf("Looking at a list of %d candidates\n", candidates.size());
        List<GeoInfo> stuffFound = Lists.newArrayList();
        for (GeoInfo candidate: candidates) {
            if (candidate.multiPolygon.contains(geometry))
                stuffFound.add(candidate);
        }
        return stuffFound;
    }

    Schema schema = null;

    @Override
    public GenericRecord apply(GenericRecord genericRecord) {
        if (schema == null) {
            // hack to copy the schema
            String oldSchemaString = schema.toString();
            schema = (new Schema.Parser()).parse(oldSchemaString);
            List<Schema.Field> fields = Lists.newArrayList(schema.getFields());
            fields.add(new Schema.Field(
                    locationNameKey,
                    Schema.create(Schema.Type.STRING),
                    "value from reverse lookup",
                    null));
            schema.setFields(fields);
        }

        Double latitude = (Double) genericRecord.get(latitudeKey);
        Double longitude = (Double) genericRecord.get(longitudeKey);
        String neighborhood =findGeoInfoForPoint(latitude, longitude).name;
        GenericRecord newRecord = (GenericRecord) GenericData.get().newRecord((Object) genericRecord, schema);
        newRecord.put(locationNameKey, neighborhood);
        return newRecord;
    }
}
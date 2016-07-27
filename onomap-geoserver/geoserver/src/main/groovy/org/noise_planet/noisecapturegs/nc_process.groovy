/*
 * This file is part of the NoiseCapture application and OnoMap system.
 *
 * The 'OnoMaP' system is led by Lab-STICC and Ifsttar and generates noise maps via
 * citizen-contributed noise data.
 *
 * This application is co-funded by the ENERGIC-OD Project (European Network for
 * Redistributing Geospatial Information to user Communities - Open Data). ENERGIC-OD
 * (http://www.energic-od.eu/) is partially funded under the ICT Policy Support Programme (ICT
 * PSP) as part of the Competitiveness and Innovation Framework Programme by the European
 * Community. The application work is also supported by the French geographic portal GEOPAL of the
 * Pays de la Loire region (http://www.geopal.org).
 *
 * Copyright (C) 2007-2016 - IFSTTAR - LAE
 * Lab-STICC – CNRS UMR 6285 Equipe DECIDE Vannes
 *
 * NoiseCapture is a free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of
 * the License, or(at your option) any later version. NoiseCapture is distributed in the hope that
 * it will be useful,but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.You should have received a copy of the GNU General Public License along with this
 * program; if not, write to the Free Software Foundation,Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301  USA or see For more information,  write to Ifsttar,
 * 14-20 Boulevard Newton Cite Descartes, Champs sur Marne F-77447 Marne la Vallee Cedex 2 FRANCE
 *  or write to scientific.computing@ifsttar.fr
 */

package org.noise_planet.noisecapturegs

import groovy.sql.Sql
import java.sql.Connection
import java.sql.SQLException


title = 'nc_process'
description = 'Recompute cells that contains new measures'

inputs = [
        locationPrecisionFilter: [name: 'locationPrecisionFilter', title: 'Ignore measurements with location precision greater than specified distance',
                           type: Float.class] ]

outputs = [
        result: [name: 'result', title: 'Processed cells', type: Integer.class]
]

def process(Connection connection, float precisionFilter) {
    float hexSize = 25.0
    connection.setAutoCommit(false)
    int processed = 0
    try {
        // List the area identifier using the new measures coordinates
        def sql = new Sql(connection)
        Set<Hex> areaIndex = new HashSet()
        def processedTrack = new HashSet()
        sql.eachRow("SELECT ST_X(ST_Transform(p.the_geom, 3857)) PTX,ST_Y(ST_Transform(p.the_geom, 3857)) PTY," +
                " q.pk_track FROM noisecapture_process_queue q, noisecapture_point p " +
                "WHERE q.pk_track = p.pk_track and p.accuracy > :precision and NOT ST_ISEMPTY(p.the_geom)",
                [precision: precisionFilter]) { row ->
            areaIndex.add(new Pos(x:row.PTX, y:row.PTY).toHex(hexSize))
            processedTrack.add(row.pk_track)
        }

        // Process areas
        for (Hex hex : areaIndex) {

            processed++
        }


        // Accept changes
        connection.commit();
    } catch (SQLException ex) {
        connection.rollback();
        throw ex
    }
    return processed
}


def run(input) {
    // Open PostgreSQL connection
    Connection connection = nc_parse.openPostgreSQLDataStoreConnection()
    try {
        return [result : process(connection, (float)input)]
    } finally {
        connection.close()
    }
}

class Pos {
    def x
    def y
    def toHex(float size) {
        def q = (x * Math.sqrt(3.0)/3.0 - y / 3.0) / size;
        def r = y * 2.0/3.0 / size;
        return new Hex(q:q, r:r, size:size).round();
    }
}

class Hex {
    final float q
    final float r
    final float size

    /**
     * @return Local coordinate of hexagon index
     */
    def toMeter() {
            def x = size * Math.sqrt(3.0) * (q + r/2.0);
            def y = size * 3.0/2.0 * r;
            return new Pos(x:x, y:y);
    }
    /**
     * @param i Vertex [0-5]
     * @return Vertex coordinate
     */
    def hex_corner(Pos center, int i) {
        def angle_deg = 60.0 * i   + 30.0;
        def angle_rad = Math.PI / 180.0 * angle_deg;
        return new Pos(x:center.x + size * Math.cos(angle_rad), y:center.y + size * Math.sin(angle_rad));
    }

    /**
     * @return Integral hex index
     */
    def round() {
        return toCube().round().toHex();
    }

    /**
     * @return Cube instance
     */
    def toCube() {
        return new Cube(x:q, y:-q-r, z:r, size:size);
    }
}

class Cube {
    final float x
    final float y
    final float z
    final float size

    def toHex() {
        return new Hex(q:x, r: z, size:size);
    }

    def round() {
        def rx = Math.round(x);
        def ry = Math.round(y);
        def rz = Math.round(z);

        def x_diff = Math.abs(rx - x);
        def y_diff = Math.abs(ry - y);
        def z_diff = Math.abs(rz - z);

        if (x_diff > y_diff && x_diff > z_diff)
            rx = -ry-rz
        else if (y_diff > z_diff)
            ry = -rx-rz
        else
            rz = -rx-ry

        return new Cube(x:rx, y:ry, z:rz, size:size);
    }
}

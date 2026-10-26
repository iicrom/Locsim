package org.iicrom.locsim.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 使用 OSM France 的 HOT 瓦片镜像。
 *
 * 本项目实测 tile.openstreetmap.org 在部分网络下无法访问，因此默认使用可访问的镜像；
 * 瓦片源名称会写入离线地图包，修改名称会导致旧离线包无法匹配。
 */
object OsmTileSource : OnlineTileSourceBase(
    "OSM-HOT",
    3,
    19,
    256,
    ".png",
    arrayOf(
        "https://a.tile.openstreetmap.fr/hot/",
        "https://b.tile.openstreetmap.fr/hot/",
        "https://c.tile.openstreetmap.fr/hot/",
    ),
    "© OpenStreetMap contributors, tiles courtesy of OpenStreetMap France",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) +
            imageFilenameEnding()
}

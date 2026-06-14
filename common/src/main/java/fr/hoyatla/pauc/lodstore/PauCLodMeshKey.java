package fr.hoyatla.pauc.lodstore;

public record PauCLodMeshKey(
	long cellPos,
	int dataHash,
	int vertexFormatId,
	byte qualityTier,
	int meshGenVersion
) {
	public boolean valid() {
		return dataHash != 0 && vertexFormatId != 0 && meshGenVersion > 0;
	}

	public String fileName() {
		return Long.toUnsignedString(cellPos, 36)
			+ "-"
			+ Integer.toUnsignedString(dataHash, 36)
			+ "-"
			+ Integer.toUnsignedString(vertexFormatId, 36)
			+ "-"
			+ Byte.toUnsignedInt(qualityTier)
			+ "-"
			+ meshGenVersion
			+ ".paucmesh";
	}
}

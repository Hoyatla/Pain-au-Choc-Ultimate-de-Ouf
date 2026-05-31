package net.irisshaders.iris.vertices;

public final class BlockRenderingContext {
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

	private BlockRenderingContext() {
	}

	public static void begin(short block, short renderType, int localPosX, int localPosY, int localPosZ) {
		CURRENT.set(new Context(block, renderType, localPosX, localPosY, localPosZ, CURRENT.get()));
	}

	public static void end() {
		Context context = CURRENT.get();
		if (context == null || context.previous == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(context.previous);
		}
	}

	public static Context current() {
		return CURRENT.get();
	}

	public static final class Context {
		private final short block;
		private final short renderType;
		private final int localPosX;
		private final int localPosY;
		private final int localPosZ;
		private final Context previous;

		private Context(short block, short renderType, int localPosX, int localPosY, int localPosZ, Context previous) {
			this.block = block;
			this.renderType = renderType;
			this.localPosX = localPosX;
			this.localPosY = localPosY;
			this.localPosZ = localPosZ;
			this.previous = previous;
		}

		public short block() {
			return block;
		}

		public short renderType() {
			return renderType;
		}

		public int localPosX() {
			return localPosX;
		}

		public int localPosY() {
			return localPosY;
		}

		public int localPosZ() {
			return localPosZ;
		}
	}
}

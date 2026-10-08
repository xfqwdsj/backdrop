package top.ltfan.backdrop.internal

/** Owns a rendering resource whose cached platform state must be refreshed for each epoch. */
internal class EpochResourceOwner<T> {
    private var resource: T? = null
    private var epoch: Int = 0

    fun attach(
        epoch: Int,
        create: () -> T,
    ): T {
        val current = resource
        if (current != null) return current

        return create().also {
            resource = it
            this.epoch = epoch
        }
    }

    fun resourceFor(
        epoch: Int,
        create: () -> T,
        release: (T) -> Unit,
    ): T {
        val current = resource ?: return attach(epoch, create)
        if (epoch == this.epoch) return current

        val replacement = create()
        resource = replacement
        this.epoch = epoch
        release(current)
        return replacement
    }

    fun release(release: (T) -> Unit) {
        val current = resource ?: return
        resource = null
        release(current)
    }
}

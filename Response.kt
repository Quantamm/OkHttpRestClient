class Response {
    var code: Code? = null
    var response: String? = null
    lateinit var fileBytes: ByteArray

    enum class Code(var value: Int) {
        Connectivity(-2), Error(-1), Pending(0), Ok(200), BadRequest(400), Unauthorized(401), Forbidden(403), NotFound(404);
    }
}

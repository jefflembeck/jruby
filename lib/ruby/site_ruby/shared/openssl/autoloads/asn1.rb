warn "OpenSSL ASN1 implementation unavailable"
warn "gem install bouncy-castle-java for full support."

module OpenSSL
  module ASN1
    class ASN1Error < OpenSSLError; end
    class ASN1Data; end
    class Primitive; end
    class Constructive; end
  end
end
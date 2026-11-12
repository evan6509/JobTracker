Google UKEY2 Java implementation, vendored without semantic source modifications.
Trailing whitespace was normalized in CryptoAnnotation.java and CryptoOps.java.

Source: https://github.com/google/ukey2
Revision: 10fc737aa901e873a3367e7e26b88eb01cd55d69
License: Apache-2.0 (see LICENSE and source headers).

The Gradle integration is maintained by JobTracker. Protobuf Java sources are generated
from the unmodified upstream schemas. Only the Java implementation is included.
UKEY2 supplies P-256/SHA-512 authenticated pairing with user comparison and the
AES-256-CBC/HMAC-SHA256 D2D channel with directional keys and sequence numbers.

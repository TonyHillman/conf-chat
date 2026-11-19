lib\openchord.jar is Open Chord 1.0.5 (University of Bamberg, GPL - see license.txt), compiled for Java 8+
from the source in this folder, unmodified. log4j is not bundled and not needed: Conf-Chat switches
Open Chord's logging off.

This snapshot already contains the fix for the lone-node crash in NodeImpl.insertEntry that the original
open-chord_1.0.5.zip still had ("... getPredecessor() is null" when the first node stores something).

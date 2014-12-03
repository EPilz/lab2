Client:
------
liest die Befehle über die Shell ein, leitet sie über eine TCP Verbindung an den cloudController weiter
und gibt den response wieder über die Shell aus.

Node:
-----
Beinhaltet einen TimerTask welcher in regelmäßigen Abständen !alive Messages an den cloudController sendet (UDP). 
Kommt ein Request für eine Berechnung, wird ein neuer NodeRequestThread gestartet welche den Term berechnet und das
Ergebnis zurückschickt, anschließend wird der socket wieder geschlossen und der Thread beendet sich.


CloudController:
---------------
Anfangs werden gleich zwei Threads gestartet, welche sich um die Kommunikation kümmern. Der ClientCommunicationThread startet 
jeweils einen neuen ClientConnectionThread wenn eine TCP Verbindung zu einem Client akzeptiert wird, dieser nimmt dann die einzelnen Befehle des 
Clients entgegen und arbeitet sie ab. 
Der NodeCommunicationThread nimmt die !alive Messages der Nodes entgegen und prüft in regelmäßigen Abständen  mithilfe eines TimerTask ob die
Nodes noch online sind.

In ClientInfo werden die jeweiligen Information bezüglich eines Clients gespeichert (credits, status,..) und 
in NodeInfo bezüglich der Nodes (tcpPort, status, usage,…) in den Communication Threads befindet sich dann jeweils 
eine ConcurrentHashMap welche diese Informationen dann für alle verwaltet.



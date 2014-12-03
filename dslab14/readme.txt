Client:
------
liest die Befehle �ber die Shell ein, leitet sie �ber eine TCP Verbindung an den cloudController weiter
und gibt den response wieder �ber die Shell aus.

Node:
-----
Beinhaltet einen TimerTask welcher in regelm��igen Abst�nden !alive Messages an den cloudController sendet (UDP). 
Kommt ein Request f�r eine Berechnung, wird ein neuer NodeRequestThread gestartet welche den Term berechnet und das
Ergebnis zur�ckschickt, anschlie�end wird der socket wieder geschlossen und der Thread beendet sich.


CloudController:
---------------
Anfangs werden gleich zwei Threads gestartet, welche sich um die Kommunikation k�mmern. Der ClientCommunicationThread startet 
jeweils einen neuen ClientConnectionThread wenn eine TCP Verbindung zu einem Client akzeptiert wird, dieser nimmt dann die einzelnen Befehle des 
Clients entgegen und arbeitet sie ab. 
Der NodeCommunicationThread nimmt die !alive Messages der Nodes entgegen und pr�ft in regelm��igen Abst�nden  mithilfe eines TimerTask ob die
Nodes noch online sind.

In ClientInfo werden die jeweiligen Information bez�glich eines Clients gespeichert (credits, status,..) und 
in NodeInfo bez�glich der Nodes (tcpPort, status, usage,�) in den Communication Threads befindet sich dann jeweils 
eine ConcurrentHashMap welche diese Informationen dann f�r alle verwaltet.



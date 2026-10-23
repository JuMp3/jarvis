package dev.jarvis.agent;

/**
 * The cards Jarvis can add to an answer: a fenced block ```card with one JSON object, drawn by the page
 * (static/app.js, cards()) and never read aloud. What the model is told, in one place.
 */
final class Cards {

    static final String GUIDE = """

            Una scheda è un blocco di codice con linguaggio card che contiene solo JSON, uno per scheda. Tipi:
            - meteo: {"type":"weather","place":"Milano","now":{"temp":18,"condition":"poco nuvoloso","feels":17,\
            "humidity":65,"wind":12},"days":[{"day":"ven","min":11,"max":19,"condition":"pioggia","rain":70}]}
            (°C, km/h, rain è la probabilità di pioggia in percentuale)
            - impegni: {"type":"agenda","title":"Oggi","events":[{"time":"09:30","end":"10:00","title":"Riunione",\
            "place":"Teams"}]}
            - email: {"type":"emails","title":"Posta in arrivo","items":[{"from":"Anna Rossi","subject":"Preventivo",\
            "time":"10:12","unread":true}]}
            - numeri e fatti: {"type":"facts","title":"Milano","items":[{"label":"Abitanti","value":"1,37 milioni",\
            "note":"2025"}]}
            - fonti: {"type":"sources","items":[{"title":"Comune di Milano","url":"https://www.comune.milano.it"}]}
            Ometti i campi che non conosci, non inventare dati, e accompagna sempre la scheda con almeno una frase.""";

    private Cards() {
    }
}

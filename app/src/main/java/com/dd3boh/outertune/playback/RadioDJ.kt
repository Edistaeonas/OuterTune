package com.dd3boh.outertune.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class RadioDJ(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onFinished: (() -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("RadioDJ", "Language not supported")
            } else {
                isInitialized = true
                setupProgressListener()
            }
        } else {
            Log.e("RadioDJ", "Initialization failed")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                scope.launch {
                    onFinished?.invoke()
                    onFinished = null
                }
            }

            override fun onError(utteranceId: String?) {
                scope.launch {
                    onFinished?.invoke()
                    onFinished = null
                }
            }
        })
    }

    fun speakAnnouncement(
        metadata: MediaMetadata,
        style: String, // "enthusiastic", "serious", "natural", "random"
        language: String, // language tag, e.g. "en-US"
        onFinished: () -> Unit
    ) {
        if (!isInitialized) {
            onFinished()
            return
        }

        this.onFinished = onFinished

        val locale = if (language == "SYSTEM_DEFAULT") Locale.getDefault() else Locale.forLanguageTag(language)

        // --- DIAGNOSTIC LOGS ---
        Log.i("RadioDJ", "--- START ANNOUNCEMENT DATA CHECK ---")
        Log.i("RadioDJ", "Title: ${metadata.title}")
        Log.i("RadioDJ", "Artist: ${metadata.artists.joinToString { it.name }}")
        Log.i("RadioDJ", "Trigger Artist: ${metadata.parentArtist}")
        Log.i("RadioDJ", "Album: ${metadata.album?.title}")
        Log.i("RadioDJ", "Year: ${metadata.year}")
        Log.i("RadioDJ", "--- END DATA CHECK ---")

        Log.i("RadioDJ", "Speaking announcement. Locale: $locale, Language property: ${locale.language}")
        tts?.setLanguage(locale)

        val script = generateScript(metadata, locale)
        Log.i("RadioDJ", "Generated script: $script")
        
        val effectiveStyle = if (style == "random") listOf("natural", "enthusiastic", "serious").random() else style
        
        applyStyle(effectiveStyle, locale)

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "announcement")
        tts?.speak(script, TextToSpeech.QUEUE_FLUSH, params, "announcement")
    }

    private fun generateScript(metadata: MediaMetadata, locale: Locale): String {
        val title = metadata.title
        val artistsList = metadata.artists.map { it.name }
        val lang = locale.language.lowercase()
        
        // Artist joining depends on language
        val artist = when {
            lang.startsWith("fr") -> if (artistsList.size > 1) artistsList.dropLast(1).joinToString(", ") + " et " + artistsList.last() else artistsList.firstOrNull()
            lang.startsWith("es") -> if (artistsList.size > 1) artistsList.dropLast(1).joinToString(", ") + " y " + artistsList.last() else artistsList.firstOrNull()
            lang.startsWith("de") -> if (artistsList.size > 1) artistsList.dropLast(1).joinToString(", ") + " und " + artistsList.last() else artistsList.firstOrNull()
            lang.startsWith("it") || lang.startsWith("pt") -> if (artistsList.size > 1) artistsList.dropLast(1).joinToString(", ") + " e " + artistsList.last() else artistsList.firstOrNull()
            else -> if (artistsList.size > 1) artistsList.dropLast(1).joinToString(", ") + " and " + artistsList.last() else artistsList.firstOrNull()
        } ?: "Unknown Artist"

        val parentArtist = metadata.parentArtist
        val album = metadata.album?.title?.takeIf { !it.startsWith("§") }
        val year = metadata.year?.toString()

        // Robust check: Is the artist of this song the same as the artist that triggered this radio selection?
        // We normalize both names (trim, lowercase, remove "The" prefix) for a reliable comparison.
        val isOriginArtistMatch = parentArtist != null && metadata.artists.any { artist ->
            val songArtistClean = artist.name.trim().lowercase().removePrefix("the ").trim()
            val triggerArtistClean = parentArtist.trim().lowercase().removePrefix("the ").trim()
            songArtistClean == triggerArtistClean
        }

        val templates = when {
            lang.startsWith("fr") -> if (parentArtist != null && !isOriginArtistMatch) {
                listOf(
                    "Parce que vous aimez $parentArtist, voici $title par $artist.",
                    "Puisque vous êtes fan de $parentArtist, vous pourriez apprécier ceci : $title par $artist.",
                    "Inspiré par votre intérêt pour $parentArtist, voici maintenant $title de $artist.",
                    "Si vous aimez $parentArtist, restez à l'écoute pour $title par $artist.",
                    "On continue dans l'ambiance de $parentArtist avec $title par $artist.",
                    "Pour les fans de $parentArtist, voici une pépite : $title par $artist.",
                    "Dans la lignée de $parentArtist, écoutons $title de $artist.",
                    "Vous aimez $parentArtist ? Alors vous allez adorer $title par $artist.",
                    "On reste avec $parentArtist en tête pour découvrir $title de $artist.",
                    "Après $parentArtist, voici $title interprété par $artist.",
                    "On enchaîne avec $title de $artist, car on sait que vous appréciez $parentArtist.",
                    "Pour faire suite à $parentArtist, voici le talentueux $artist avec $title."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Issu de l'album $album sorti en $year, voici $title par $artist.",
                            "On remonte en $year avec l'album $album pour écouter $title de $artist.",
                            "Voici $title de $artist, un extrait de l'album $album de $year.",
                            "Écoutons $title par $artist, tiré de l'album $album paru en $year.",
                            "Un classique de l'année $year : $title de l'album $album par $artist.",
                            "Voici un moment fort de $year, $title par $artist sur l'album $album.",
                            "Souvenir de $year avec $title de $artist, extrait de $album.",
                            "En $year, l'album $album nous offrait ce titre : $title par $artist.",
                            "Redécouvrons $title de $artist, sur l'album $album de $year."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Tiré de l'album $album, voici $title par $artist.",
                            "On écoute $title de $artist, extrait de l'album $album.",
                            "Voici $title par $artist, de l'album $album.",
                            "Découvrons un titre de l'album $album : $title par $artist.",
                            "Place à l'album $album avec le morceau $title de $artist.",
                            "On continue avec $title, un extrait de l'album $album par $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Un morceau de $year : voici $title par $artist.",
                            "On retourne en $year pour écouter $title de $artist.",
                            "Voici $title par $artist, sorti en $year.",
                            "Voyage en $year avec $title interprété par $artist.",
                            "Retour sur l'année $year avec $title de $artist.",
                            "Un succès de $year : $title par $artist."
                        )
                    } else base
                }
            } else {
                listOf(
                    "Tout de suite, nous avons $title par $artist.",
                    "Ensuite sur OuterTune, $title de $artist.",
                    "À venir, un super morceau : $title par $artist.",
                    "On enchaîne avec $title par $artist.",
                    "Écoutons $title du talentueux $artist.",
                    "Restez à l'écoute pour $title par $artist.",
                    "Maintenant, $title par $artist.",
                    "Place à la musique avec $title de $artist.",
                    "C'est au tour de $artist de nous régaler avec $title.",
                    "On continue la playlist avec $title par $artist.",
                    "Découvrons ensemble $title de $artist.",
                    "Voici une superbe piste : $title par $artist.",
                    "On monte le son pour $title de $artist.",
                    "L'excellent $artist nous propose $title.",
                    "Place à $artist avec le titre $title.",
                    "Tout de suite, $title, signé $artist."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Issu de l'album $album de $year, voici $title par $artist.",
                            "Un classique de $year tiré de $album : $title par $artist.",
                            "Voici $title de $artist, de l'album $album sorti en $year.",
                            "En $year, $artist sortait $album. En voici $title.",
                            "Retrouvons $artist avec $title, sur l'album $album de $year.",
                            "Voici $title, un titre de $artist extrait de $album en $year."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Extrait de l'album $album, voici $title par $artist.",
                            "On écoute $title de $artist, de l'album $album.",
                            "Place à l'album $album avec $title de $artist.",
                            "De l'album $album, voici le morceau $title par $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Sorti en $year, voici $title par $artist.",
                            "On repart en $year avec $title de $artist.",
                            "Voici $title par $artist, un titre de $year.",
                            "Direction l'année $year avec $title par $artist."
                        )
                    } else base
                }
            }
            lang.startsWith("es") -> if (parentArtist != null && !isOriginArtistMatch) {
                listOf(
                    "Porque te gusta $parentArtist, aquí tienes $title de $artist.",
                    "Como eres fan de $parentArtist, podrías disfrutar esto: $title de $artist.",
                    "Inspirado por tu interés en $parentArtist, a continuación llega $title de $artist.",
                    "Si disfrutas de $parentArtist, quédate para escuchar $title de $artist.",
                    "Siguiendo con el estilo de $parentArtist, aquí está $title de $artist.",
                    "Para los seguidores de $parentArtist, llega esta joya: $title de $artist.",
                    "En la línea de $parentArtist, escuchemos $title de $artist.",
                    "¿Te gusta $parentArtist? Entonces te encantará $title de $artist.",
                    "Seguimos con $parentArtist en mente para descubrir $title de $artist.",
                    "Tras $parentArtist, aquí llega $title interpretado por $artist.",
                    "Continuamos con $title de $artist, ya que sabemos que aprecias a $parentArtist."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Del álbum $album lanzado en $year, aquí está $title de $artist.",
                            "Volvemos a $year con el álbum $album para escuchar $title de $artist.",
                            "Aquí suena $title de $artist, incluido en el álbum $album de $year.",
                            "Escuchemos $title de $artist, del álbum $album publicado en $year.",
                            "Un clásico del año $year: $title del disco $album por $artist."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Extraído del álbum $album, aquí está $title de $artist.",
                            "Escuchamos $title de $artist, del álbum $album.",
                            "Aquí tienes $title de $artist, del trabajo $album.",
                            "Descubramos un tema del álbum $album: $title de $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Un tema de $year: aquí está $title de $artist.",
                            "Regresamos a $year para escuchar $title de $artist.",
                            "Aquí suena $title de $artist, lanzado en $year.",
                            "Viaje a $year con $title interpretado por $artist."
                        )
                    } else base
                }
            } else {
                listOf(
                    "A continuación, tenemos $title de $artist.",
                    "Lo próximo en OuterTune, $title de $artist.",
                    "Viene un gran tema: $title de $artist.",
                    "Seguimos adelante con $title de $artist.",
                    "Escuchemos $title del talentoso $artist.",
                    "No te vayas, ahora suena $title de $artist.",
                    "Ahora suena $title de $artist.",
                    "Turno para la música con $title de $artist.",
                    "Es el momento de que $artist nos deleite con $title.",
                    "Continuamos la lista con $title de $artist.",
                    "Descubramos juntos $title de $artist.",
                    "Aquí va una pista excelente: $title de $artist.",
                    "Subimos el volumen para $title de $artist.",
                    "El gran $artist nos trae $title.",
                    "Damos paso a $artist con el tema $title."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Del álbum $album de $year, aquí está $title de $artist.",
                            "Un clásico de $year del disco $album: $title de $artist.",
                            "Aquí suena $title de $artist, del álbum $album lanzado en $year.",
                            "En $year, $artist lanzaba $album. De ahí escuchamos $title.",
                            "Recuperamos a $artist con $title, en el álbum $album de $year."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Extraído del álbum $album, aquí está $title de $artist.",
                            "Escuchamos $title de $artist, del disco $album.",
                            "Damos paso al álbum $album con $title de $artist.",
                            "Del disco $album, aquí llega el tema $title de $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Lanzado en $year, aquí está $title de $artist.",
                            "Volvemos a $year con $title de $artist.",
                            "Aquí tienes $title de $artist, un éxito de $year.",
                            "Rumbo al año $year con $title de $artist."
                        )
                    } else base
                }
            }
            lang.startsWith("de") -> if (parentArtist != null && !isOriginArtistMatch) {
                listOf(
                    "Weil du $parentArtist magst, ist hier $title von $artist.",
                    "Da du ein Fan von $parentArtist bist, könnte dir das gefallen: $title von $artist.",
                    "Inspiriert durch dein Interesse an $parentArtist, kommt als Nächstes $title von $artist.",
                    "Wenn dir $parentArtist gefällt, bleib dran für $title von $artist.",
                    "Weiter geht's mit dem $parentArtist-Vibe: hier ist $title von $artist.",
                    "Für alle Fans von $parentArtist haben wir hier: $title von $artist.",
                    "Ganz im Stil von $parentArtist hören wir jetzt $title von $artist.",
                    "Dir gefällt $parentArtist? Dann wird dir auch $title von $artist gefallen.",
                    "Wir bleiben bei $parentArtist und entdecken $title von $artist.",
                    "Nach $parentArtist kommt jetzt $title, präsentiert von $artist.",
                    "Wir machen weiter mit $title von $artist, passend zu deinem Interesse an $parentArtist."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Vom Album $album aus dem Jahr $year, hier ist $title von $artist.",
                            "Wir gehen zurück ins Jahr $year zum Album $album für $title von $artist.",
                            "Hier ist $title von $artist aus dem Album $album aus dem Jahr $year.",
                            "Hören wir $title von $artist, vom Album $album, erschienen $year.",
                            "Ein Klassiker aus $year: $title vom Album $album von $artist."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Aus dem Album $album, hier ist $title von $artist.",
                            "Wir hören $title von $artist vom Album $album.",
                            "Hier ist $title von $artist, vom Werk $album.",
                            "Entdecken wir einen Song aus dem Album $album: $title von $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Ein Song aus dem Jahr $year: hier ist $title von $artist.",
                            "Zurück ins Jahr $year für $title von $artist.",
                            "Hier ist $title von $artist, veröffentlicht $year.",
                            "Eine Reise ins Jahr $year mit $title von $artist."
                        )
                    } else base
                }
            } else {
                listOf(
                    "Als Nächstes haben wir $title von $artist.",
                    "Als Nächstes auf OuterTune: $title von $artist.",
                    "Demnächst ein toller Track: $title von $artist.",
                    "Weiter geht es mit $title von $artist.",
                    "Hören wir uns $title vom talentierten $artist an.",
                    "Bleib dran für $title von $artist.",
                    "Jetzt läuft $title von $artist.",
                    "Musik ab für $title von $artist.",
                    "Jetzt zeigt uns $artist sein Können mit $title.",
                    "Weiter in der Playlist mit $title von $artist.",
                    "Entdecken wir gemeinsam $title von $artist.",
                    "Hier ist ein großartiger Song: $title von $artist.",
                    "Lauter machen für $title von $artist.",
                    "Der fantastische $artist bringt uns $title.",
                    "Bühne frei für $artist mit dem Titel $title."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Vom Album $album aus $year, hier ist $title von $artist.",
                            "Ein Klassiker von $year vom Album $album: $title von $artist.",
                            "Hier ist $title von $artist, vom Album $album aus dem Jahr $year.",
                            "Im Jahr $year veröffentlichte $artist $album. Daraus hören wir $title.",
                            "Wir hören $artist mit $title vom ${year}er Album $album."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Aus dem Album $album, hier ist $title von $artist.",
                            "Wir hören $title von $artist vom Album $album.",
                            "Zeit für das Album $album mit $title von $artist.",
                            "Vom Werk $album hören wir nun $title von $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Erschienen $year, hier ist $title von $artist.",
                            "Zurück ins Jahr $year mit $title von $artist.",
                            "Hier ist $title von $artist, ein Titel aus $year.",
                            "Ab ins Jahr $year mit $title von $artist."
                        )
                    } else base
                }
            }
            lang.startsWith("it") -> if (parentArtist != null && !isOriginArtistMatch) {
                listOf(
                    "Visto che ti piace $parentArtist, ecco $title di $artist.",
                    "Siccome sei un fan di $parentArtist, potrebbe piacerti questo: $title di $artist.",
                    "Ispirato dal tuo interesse per $parentArtist, il prossimo brano è $title di $artist.",
                    "Se ti piace $parentArtist, resta con noi per $title di $artist.",
                    "Continuando con lo stile di $parentArtist, ecco $title di $artist.",
                    "Per i fan di $parentArtist, ecco una chicca: $title di $artist.",
                    "Sulla scia di $parentArtist, ascoltiamo $title di $artist.",
                    "Ti piace $parentArtist? Allora adorerai $title di $artist.",
                    "Restiamo in tema $parentArtist per scoprire $title di $artist.",
                    "Dopo $parentArtist, ecco $title interpretato da $artist.",
                    "Proseguiamo con $title di $artist, sapendo che apprezzi $parentArtist."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Dall'album $album uscito nel $year, ecco $title di $artist.",
                            "Torniamo al $year con l'album $album per ascoltare $title di $artist.",
                            "Ecco $title di $artist, contenuto nell'album $album del $year.",
                            "Ascoltiamo $title di $artist, dall'album $album pubblicato nel $year.",
                            "Un classico del $year: $title dal disco $album di $artist."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Tratto dall'album $album, ecco $title di $artist.",
                            "Ascoltiamo $title di $artist dall'album $album.",
                            "Ecco $title di $artist, dall'opera $album.",
                            "Scopriamo un brano dall'album $album: $title di $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Un brano del $year: ecco $title di $artist.",
                            "Torniamo al $year per ascoltare $title di $artist.",
                            "Ecco $title di $artist, uscito nel $year.",
                            "Viaggio nel $year con $title interpretato da $artist."
                        )
                    } else base
                }
            } else {
                listOf(
                    "A seguire, abbiamo $title di $artist.",
                    "Prossimamente su OuterTune, $title di $artist.",
                    "In arrivo, un grande brano: $title di $artist.",
                    "Andando avanti, ecco $title di $artist.",
                    "Ascoltiamo $title dal talentuoso $artist.",
                    "Rimani sintonizzato per $title di $artist.",
                    "Ora in onda, $title di $artist.",
                    "Spazio alla musica con $title di $artist.",
                    "È il turno di $artist di deliziarci con $title.",
                    "Continuiamo la playlist con $title di $artist.",
                    "Scopriamo insieme $title di $artist.",
                    "Ecco una splendida traccia: $title di $artist.",
                    "Alziamo il volume per $title di $artist.",
                    "Il grande $artist ci propone $title.",
                    "Spazio a $artist con il brano $title."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Dall'album $album del $year, ecco $title di $artist.",
                            "Un classico del $year dal disco $album: $title di $artist.",
                            "Ecco $title di $artist, dall'album $album uscito nel $year.",
                            "Nel $year, $artist pubblicava $album. Da qui ascoltiamo $title.",
                            "Ritroviamo $artist con $title, nel disco $album del $year."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Tratto dall'album $album, ecco $title di $artist.",
                            "Ascoltiamo $title di $artist dall'album $album.",
                            "Passiamo all'album $album con $title di $artist.",
                            "Dall'opera $album, ecco la traccia $title di $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Uscito nel $year, ecco $title di $artist.",
                            "Torniamo al $year con $title di $artist.",
                            "Ecco $title di $artist, un titolo del $year.",
                            "Direzione anno $year con $title di $artist."
                        )
                    } else base
                }
            }
            lang.startsWith("pt") -> if (parentArtist != null && !isOriginArtistMatch) {
                listOf(
                    "Porque você gosta de $parentArtist, aqui está $title de $artist.",
                    "Como você é fã de $parentArtist, talvez curta esta: $title de $artist.",
                    "Inspirado no seu interesse em $parentArtist, a seguir temos $title de $artist.",
                    "Se você curte $parentArtist, continue ouvindo para $title de $artist.",
                    "Continuando no clima de $parentArtist, aqui está $title de $artist.",
                    "Para os fãs de $parentArtist, chega esta pérola: $title de $artist.",
                    "Na linha de $parentArtist, vamos ouvir $title de $artist.",
                    "Gosta de $parentArtist? Então você vai adorar $title de $artist.",
                    "Seguimos com $parentArtist em mente para descobrir $title de $artist.",
                    "Depois de $parentArtist, aqui vem $title interpretado por $artist.",
                    "Continuamos com $title de $artist, já que sabemos que você aprecia $parentArtist."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Do álbum $album lançado em $year, aqui está $title de $artist.",
                            "Voltamos a $year com o álbum $album para ouvir $title de $artist.",
                            "Aqui soa $title de $artist, incluído no álbum $album de $year.",
                            "Vamos ouvir $title de $artist, do álbum $album publicado em $year.",
                            "Um clássico do ano $year: $title do disco $album por $artist."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Extraído do álbum $album, aqui está $title de $artist.",
                            "Ouvimos $title de $artist, do álbum $album.",
                            "Aqui tem $title de $artist, do trabalho $album.",
                            "Vamos descobrir uma faixa do álbum $album: $title de $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Um tema de $year: aqui está $title de $artist.",
                            "Regressamos a $year para ouvir $title de $artist.",
                            "Aqui soa $title de $artist, lançado em $year.",
                            "Viagem ao ano $year com $title interpretado por $artist."
                        )
                    } else base
                }
            } else {
                listOf(
                    "A seguir, temos $title de $artist.",
                    "O próximo no OuterTune, $title de $artist.",
                    "Vem aí um grande tema: $title de $artist.",
                    "Seguimos em frente com $title de $artist.",
                    "Vamos ouvir $title do talentoso $artist.",
                    "Não saia daí, agora toca $title de $artist.",
                    "Agora ouvimos $title de $artist.",
                    "Vez da música com $title de $artist.",
                    "É o momento de $artist nos brindar com $title.",
                    "Continuamos a lista com $title de $artist.",
                    "Vamos descobrir juntos $title de $artist.",
                    "Aqui vai uma faixa excelente: $title de $artist.",
                    "Aumente o volume para $title de $artist.",
                    "O grande $artist nos traz $title.",
                    "Damos lugar a $artist com o tema $title."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "Do álbum $album de $year, aqui está $title de $artist.",
                            "Um clássico de $year do disco $album: $title de $artist.",
                            "Aqui soa $title de $artist, do álbum $album lançado em $year.",
                            "Em $year, $artist lançava $album. Daí ouvimos $title.",
                            "Recuperamos $artist com $title, no álbum $album de $year."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Extraído do álbum $album, aqui está $title de $artist.",
                            "Ouvimos $title de $artist, do disco $album.",
                            "Damos lugar ao álbum $album com $title de $artist.",
                            "Do disco $album, aqui chega o tema $title de $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Lançado em $year, aqui está $title de $artist.",
                            "Voltamos a $year com $title de $artist.",
                            "Aqui tem $title de $artist, um sucesso de $year.",
                            "Rumo ao ano $year com $title de $artist."
                        )
                    } else base
                }
            }
            else -> if (parentArtist != null && !isOriginArtistMatch) {
                listOf(
                    "Because you like $parentArtist, here is $title by $artist.",
                    "Since you're a fan of $parentArtist, you might enjoy this: $title by $artist.",
                    "Inspired by your interest in $parentArtist, coming up next is $title from $artist.",
                    "If you enjoy $parentArtist, stick around for $title by $artist.",
                    "Continuing with that $parentArtist vibe, here's $title by $artist.",
                    "For fans of $parentArtist, here's a gem: $title by $artist.",
                    "In the style of $parentArtist, let's hear $title from $artist.",
                    "Love $parentArtist? Then you'll love $title by $artist.",
                    "We're keeping $parentArtist in mind with $title by $artist.",
                    "Following $parentArtist, here is $title performed by $artist.",
                    "Next up is $title by $artist, as we know you like $parentArtist."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "From the album $album released in $year, here's $title by $artist.",
                            "Going back to $year with the album $album for $title by $artist.",
                            "Here's $title by $artist, featured on the $year album $album.",
                            "Let's listen to $title by $artist, from the album $album published in $year.",
                            "A $year classic: $title from the album $album by $artist."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Taken from the album $album, here is $title by $artist.",
                            "We're listening to $title by $artist from the album $album.",
                            "Here's $title by $artist, from the work $album.",
                            "Let's discover a track from $album: $title by $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "A track from $year: here is $title by $artist.",
                            "Back to $year for $title by $artist.",
                            "Here is $title by $artist, released in $year.",
                            "A trip to $year with $title by $artist."
                        )
                    } else base
                }
            } else {
                listOf(
                    "Up next, we have $title by $artist.",
                    "Next on OuterTune, $title from $artist.",
                    "Coming up, a great track: $title by $artist.",
                    "Moving right along, here is $title by $artist.",
                    "Let's listen to $title from the talented $artist.",
                    "Stay tuned for $title by $artist.",
                    "Now playing, $title by $artist.",
                    "Time for music with $title by $artist.",
                    "It's $artist's turn to treat us with $title.",
                    "Continuing the playlist with $title by $artist.",
                    "Let's discover $title by $artist together.",
                    "Here's a great track: $title by $artist.",
                    "Turn it up for $title by $artist.",
                    "The excellent $artist brings us $title.",
                    "Giving way to $artist with the title $title."
                ).let { base ->
                    if (album != null && year != null) {
                        base + listOf(
                            "From the album $album from $year, here's $title by $artist.",
                            "A $year classic from the record $album: $title by $artist.",
                            "Here's $title by $artist, from the album $album released in $year.",
                            "In $year, $artist released $album. From that, we hear $title.",
                            "We're catching $artist with $title on the $year album $album."
                        )
                    } else if (album != null) {
                        base + listOf(
                            "Extract from the album $album, here is $title by $artist.",
                            "We're hearing $title by $artist from the disc $album.",
                            "Giving way to the album $album with $title by $artist.",
                            "From the work $album, here is $title by $artist."
                        )
                    } else if (year != null) {
                        base + listOf(
                            "Released in $year, here's $title by $artist.",
                            "Going back to $year with $title by $artist.",
                            "Here's $title by $artist, a title from $year.",
                            "Heading to the year $year with $title by $artist."
                        )
                    } else base
                }
            }
        }

        return templates.random()
    }

    private fun applyStyle(style: String, locale: Locale) {
        // Voice selection based on locale
        val voices = tts?.voices
        
        Log.i("RadioDJ", "Searching for voice in ${locale.toLanguageTag()}. Available voices total: ${voices?.size}")
        
        // 1. Prioritize full locale match (e.g., "en-gb" or "fr-ca")
        // Note: locale.toLanguageTag() usually returns "en-GB" (case preserved), 
        // while voice names are usually lowercase like "en-gb-x-..."
        val fullLocaleTag = locale.toLanguageTag().lowercase().replace("_", "-")
        var targetVoices = voices?.filter { 
            it.name.lowercase().startsWith(fullLocaleTag) 
        }
        
        if (targetVoices.isNullOrEmpty()) {
            Log.i("RadioDJ", "No full locale match for $fullLocaleTag, falling back to language ${locale.language}")
            // 2. Fallback to language match (e.g., "en" or "fr")
            targetVoices = voices?.filter { 
                it.locale.language.lowercase() == locale.language.lowercase() 
            }
        }
        
        Log.i("RadioDJ", "Found ${targetVoices?.size} candidate voices")
        
        val targetVoice = targetVoices?.randomOrNull()
        
        if (targetVoice != null) {
            Log.i("RadioDJ", "Selected voice: ${targetVoice.name}")
            tts?.voice = targetVoice
        } else {
            Log.w("RadioDJ", "No suitable voice found for ${locale.toLanguageTag()}, using engine default")
        }

        val baseSpeechRate = when (style.lowercase()) {
            "enthusiastic" -> 1.2f
            "serious" -> 0.9f
            else -> 1.0f
        }
        
        val basePitch = when (style.lowercase()) {
            "enthusiastic" -> 1.3f
            "serious" -> 0.8f
            else -> 1.0f
        }

        // Add slight random variation
        val randomVariation = (Math.random() * 0.1 - 0.05).toFloat()
        tts?.setPitch(basePitch + randomVariation)
        tts?.setSpeechRate(baseSpeechRate + randomVariation)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

use std::fs;
use std::io;
use std::path::Path;

pub const FILE_NAME: &str = "last_recording.wav";

// PCM, mono, 16 kHz, 32000 bytes/sec, 2 bytes/frame, 16 bits/sample.
const PCM_FORMAT: [u8; 16] = [1, 0, 1, 0, 128, 62, 0, 0, 0, 125, 0, 0, 2, 0, 16, 0];

pub fn encode_wav(samples: &[f32]) -> Vec<u8> {
    let data_size = (samples.len() * 2) as u32;
    let mut wav = Vec::with_capacity(44 + data_size as usize);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&(36 + data_size).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes());
    wav.extend_from_slice(&PCM_FORMAT);
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_size.to_le_bytes());
    for &sample in samples {
        let pcm = (sample.clamp(-1.0, 1.0) * 32767.0).round() as i16;
        wav.extend_from_slice(&pcm.to_le_bytes());
    }
    wav
}

pub fn decode_wav(bytes: &[u8]) -> Option<Vec<f32>> {
    if bytes.get(..4)? != b"RIFF" || bytes.get(8..12)? != b"WAVE" {
        return None;
    }
    let riff_size = u32::from_le_bytes(bytes[4..8].try_into().ok()?) as usize;
    if riff_size != bytes.len() - 8 {
        return None;
    }

    let mut chunks = &bytes[12..];
    let mut has_format = false;
    let mut data = None;
    while !chunks.is_empty() {
        let id = chunks.get(..4)?;
        let size = u32::from_le_bytes(chunks.get(4..8)?.try_into().ok()?) as usize;
        let end = 8usize.checked_add(size)?;
        let payload = chunks.get(8..end)?;
        match id {
            b"fmt " if !has_format && payload == PCM_FORMAT => has_format = true,
            b"data" if data.is_none() && size > 0 && size % 2 == 0 => data = Some(payload),
            b"fmt " | b"data" => return None,
            _ => {}
        }
        chunks = chunks.get(end.checked_add(size % 2)?..)?;
    }

    if !has_format {
        return None;
    }
    Some(
        data?
            .chunks_exact(2)
            .map(|sample| i16::from_le_bytes([sample[0], sample[1]]) as f32 / 32768.0)
            .collect(),
    )
}

pub fn save(path: &Path, samples: &[f32]) -> io::Result<()> {
    // One fixed temp name: saves never overlap (a new recording can't start
    // while a transcription worker runs), and a file left by a killed
    // process is overwritten by the next save, deleted by Clear and
    // excluded from backup, instead of piling up under unique names.
    let mut temp_name = path.as_os_str().to_owned();
    temp_name.push(".tmp");
    let temp = Path::new(&temp_name);
    let result = fs::write(temp, encode_wav(samples)).and_then(|()| fs::rename(temp, path));
    if result.is_err() {
        let _ = fs::remove_file(temp);
    }
    result
}

pub fn load(path: &Path) -> Option<Vec<f32>> {
    decode_wav(&fs::read(path).ok()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_preserves_samples() {
        let samples = [0.0, 0.5, -0.5, 1.0, -1.0];
        let decoded = decode_wav(&encode_wav(&samples)).unwrap();
        assert_eq!(decoded.len(), samples.len());
        for (actual, expected) in decoded.iter().zip(samples) {
            assert!((actual - expected).abs() <= 1.0 / 32767.0 + 1e-6);
        }
    }

    #[test]
    fn clamps_out_of_range() {
        let decoded = decode_wav(&encode_wav(&[2.0, -3.0])).unwrap();
        assert!((decoded[0] - 1.0).abs() < 1e-3);
        assert!((decoded[1] + 1.0).abs() < 1e-3);
    }

    #[test]
    fn header_is_16k_mono_16bit() {
        let encoded = encode_wav(&[0.0, 0.5]);
        assert_eq!(&encoded[..4], b"RIFF");
        assert_eq!(&encoded[8..12], b"WAVE");
        assert_eq!(u16::from_le_bytes(encoded[22..24].try_into().unwrap()), 1);
        assert_eq!(
            u32::from_le_bytes(encoded[24..28].try_into().unwrap()),
            16000
        );
        assert_eq!(u16::from_le_bytes(encoded[34..36].try_into().unwrap()), 16);
        assert_eq!(encoded.len(), 48);
    }

    #[test]
    fn rejects_garbage() {
        assert!(decode_wav(b"hello").is_none());
        assert!(decode_wav(&[]).is_none());
        let mut encoded = encode_wav(&[0.5]);
        assert!(decode_wav(&encoded[..encoded.len() - 1]).is_none());
        encoded[24..28].copy_from_slice(&44100u32.to_le_bytes());
        assert!(decode_wav(&encoded).is_none());
    }

    #[test]
    fn save_then_load() {
        let dir = std::env::temp_dir().join(format!("last-recording-test-{}", std::process::id()));
        std::fs::create_dir(&dir).unwrap();
        let path = dir.join(FILE_NAME);
        assert!(load(&path).is_none());
        save(&path, &[0.0]).unwrap();
        let samples = [0.5, -0.5];
        save(&path, &samples).unwrap();
        assert_eq!(load(&path), decode_wav(&encode_wav(&samples)));
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1);
        std::fs::remove_dir_all(dir).unwrap();
    }
}

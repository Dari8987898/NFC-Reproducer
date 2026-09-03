using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

namespace BadgeNfc.StrongLink
{
    /// <summary>
    /// Adapter for an SL500A/SL500F controlled through StrongLink MasterRD.dll.
    /// The caller remains responsible for polling and recording attendance.
    /// </summary>
    public sealed class Sl500HceClient : IDisposable
    {
        private const byte RequestAll = 0x52;
        private const byte Iso14443A = (byte)'A';
        private const int MaxSdkResponseLength = 255;
        private const int ReadChunkLength = 240;

        private static readonly byte[] SelectBadgeApplication =
        {
            0x00, 0xA4, 0x04, 0x00, 0x08,
            0xF0, 0x4E, 0x46, 0x43, 0x52, 0x45, 0x50, 0x01
        };

        private readonly ushort _deviceId;
        private bool _selected;

        public Sl500HceClient(ushort deviceId)
        {
            _deviceId = deviceId;
        }

        /// <summary>
        /// Opens the virtual COM port. Do not call this if the attendance
        /// program already owns the MasterRD.dll connection.
        /// </summary>
        public static void OpenPort(int comPortNumber, int baudRate)
        {
            EnsureSuccess(Native.rf_init_com(comPortNumber, baudRate), "rf_init_com");
        }

        public static void ClosePort()
        {
            EnsureSuccess(Native.rf_ClosePort(), "rf_ClosePort");
        }

        public string GetReaderModel()
        {
            var data = new byte[MaxSdkResponseLength];
            byte length = MaxSdkResponseLength;
            EnsureSuccess(Native.rf_get_model(_deviceId, data, ref length), "rf_get_model");
            var modelBytes = data.Take(length).ToArray();
            var text = Encoding.ASCII.GetString(modelBytes).Trim('\0', ' ', '\r', '\n');
            return string.IsNullOrWhiteSpace(text) ? BitConverter.ToString(modelBytes) : text;
        }

        /// <summary>
        /// Activates an ISO14443A-4 phone and selects the Android HCE AID.
        /// Throws on SL500L or when the firmware does not expose Type A-4.
        /// </summary>
        public byte[] ActivatePhone()
        {
            EnsureSuccess(Native.rf_init_type(_deviceId, Iso14443A), "rf_init_type('A')");

            var resetData = new byte[MaxSdkResponseLength];
            byte resetLength = MaxSdkResponseLength;
            EnsureSuccess(
                Native.rf_typea_rst(_deviceId, RequestAll, resetData, ref resetLength),
                "rf_typea_rst"
            );

            Exchange(SelectBadgeApplication);
            _selected = true;
            return resetData.Take(resetLength).ToArray();
        }

        public HceInfo GetInfo()
        {
            EnsureSelected();
            var data = Exchange(new byte[] { 0x80, 0xCA, 0x00, 0x00, 0x00 });
            if (data.Length != 6)
                throw new InvalidDataException("Risposta GET INFO non valida.");

            return new HceInfo(
                data[0],
                data[1],
                (data[2] << 8) | data[3],
                (data[4] << 8) | data[5]
            );
        }

        /// <summary>Returns the UID captured from the original physical badge.</summary>
        public byte[] ReadOriginalUid()
        {
            EnsureSelected();
            return Exchange(new byte[] { 0x80, 0xCA, 0x01, 0x00, 0x00 });
        }

        /// <summary>
        /// Reads one captured MIFARE data block by its absolute block address.
        /// This replaces rf_M1_read for the phone path. Sector trailers are not stored.
        /// </summary>
        public byte[] ReadClassicBlock(ushort absoluteBlock)
        {
            EnsureSelected();
            var data = Exchange(new byte[]
            {
                0x80,
                0xB0,
                (byte)(absoluteBlock >> 8),
                (byte)absoluteBlock,
                0x10
            });
            if (data.Length != 16)
                throw new InvalidDataException("Il blocco restituito non contiene 16 byte.");
            return data;
        }

        /// <summary>Reads the complete NFR1 snapshot in SL500-safe chunks.</summary>
        public byte[] ReadSnapshot()
        {
            EnsureSelected();
            var info = GetInfo();
            using (var output = new MemoryStream(info.PayloadLength))
            {
                var offset = 0;
                while (offset < info.PayloadLength)
                {
                    var length = Math.Min(ReadChunkLength, info.PayloadLength - offset);
                    var part = Exchange(new byte[]
                    {
                        0x00,
                        0xB0,
                        (byte)(offset >> 8),
                        (byte)offset,
                        (byte)length
                    });
                    if (part.Length == 0)
                        throw new InvalidDataException("READ BINARY ha restituito zero byte.");
                    output.Write(part, 0, part.Length);
                    offset += part.Length;
                }
                return output.ToArray();
            }
        }

        public void Dispose()
        {
            if (!_selected) return;
            Native.rf_cl_deselect(_deviceId);
            _selected = false;
        }

        private byte[] Exchange(byte[] command)
        {
            var response = new byte[MaxSdkResponseLength];
            byte responseLength = MaxSdkResponseLength;
            EnsureSuccess(
                Native.rf_cos_command(
                    _deviceId,
                    command,
                    checked((byte)command.Length),
                    response,
                    ref responseLength
                ),
                "rf_cos_command"
            );

            if (responseLength < 2)
                throw new InvalidDataException("Risposta APDU priva di SW1/SW2.");

            var sw1 = response[responseLength - 2];
            var sw2 = response[responseLength - 1];
            if (sw1 != 0x90 || sw2 != 0x00)
                throw new Sl500ApduException(sw1, sw2);

            return response.Take(responseLength - 2).ToArray();
        }

        private void EnsureSelected()
        {
            if (!_selected)
                throw new InvalidOperationException("Chiamare ActivatePhone prima di leggere i dati.");
        }

        private static void EnsureSuccess(int result, string operation)
        {
            if (result != 0)
                throw new Sl500SdkException(operation, result);
        }

        private static class Native
        {
            private const string DllName = "MasterRD.dll";

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_init_com(int port, int baud);

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_ClosePort();

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_get_model(
                ushort icdev,
                [Out] byte[] data,
                ref byte length
            );

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_init_type(ushort icdev, byte type);

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_typea_rst(
                ushort icdev,
                byte model,
                [Out] byte[] data,
                ref byte length
            );

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_cos_command(
                ushort icdev,
                [In] byte[] command,
                byte commandLength,
                [Out] byte[] data,
                ref byte length
            );

            [DllImport(DllName, CallingConvention = CallingConvention.StdCall)]
            internal static extern int rf_cl_deselect(ushort icdev);
        }
    }

    public sealed class HceInfo
    {
        public HceInfo(byte protocolVersion, byte flags, int payloadLength, int blockCount)
        {
            ProtocolVersion = protocolVersion;
            Flags = flags;
            PayloadLength = payloadLength;
            BlockCount = blockCount;
        }

        public byte ProtocolVersion { get; private set; }
        public byte Flags { get; private set; }
        public int PayloadLength { get; private set; }
        public int BlockCount { get; private set; }
        public bool ContainsMifareBlocks { get { return (Flags & 0x01) != 0; } }
        public bool ContainsNdef { get { return (Flags & 0x02) != 0; } }
    }

    public sealed class Sl500SdkException : Exception
    {
        public Sl500SdkException(string operation, int result)
            : base(operation + " non riuscita. Codice SDK: " + result + ".")
        {
            Operation = operation;
            Result = result;
        }

        public string Operation { get; private set; }
        public int Result { get; private set; }
    }

    public sealed class Sl500ApduException : Exception
    {
        public Sl500ApduException(byte sw1, byte sw2)
            : base(string.Format("APDU rifiutata: {0:X2}{1:X2}.", sw1, sw2))
        {
            StatusWord = (ushort)((sw1 << 8) | sw2);
        }

        public ushort StatusWord { get; private set; }
    }
}
